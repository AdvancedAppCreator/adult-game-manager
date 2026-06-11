// JNI bridge from Kotlin to RARLAB unrar.
//
// Exposes two operations:
//   - listEntries(path, password)    -> List<UnrarEntry>
//   - extractAll(path, password, destDir, callback)
//
// extractAll calls back into Kotlin every chunk (UCM_PROCESSDATA) with the
// number of bytes processed since the last callback so the UI can report
// per-chunk progress without polling.
#include <jni.h>
#include <string>
#include <vector>
#include <cstring>
#include <android/log.h>
#include "unrar/dll.hpp"

#define LOG_TAG "UnrarJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN,  LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

struct CallbackContext {
    JNIEnv*  env;
    jobject  cb;          // global ref to UnrarProgressCallback
    jmethodID onChunk;    // void onChunk(long bytes, String currentName)
    jmethodID isCancelled; // boolean isCancelled()
    std::string password;
    bool needPasswordReported = false;
    bool cancelled = false;
};

int CALLBACK UnrarCallback(UINT msg, LPARAM userData, LPARAM p1, LPARAM p2) {
    auto* ctx = reinterpret_cast<CallbackContext*>(userData);
    if (!ctx) return 0;
    switch (msg) {
        case UCM_PROCESSDATA: {
            // p1 = data pointer, p2 = byte count for this chunk.
            jlong bytes = static_cast<jlong>(p2);
            if (ctx->cb && ctx->onChunk) {
                ctx->env->CallVoidMethod(ctx->cb, ctx->onChunk, bytes, (jstring) nullptr);
                if (ctx->env->ExceptionCheck()) {
                    ctx->env->ExceptionClear();
                    return -1;
                }
            }
            if (ctx->cb && ctx->isCancelled) {
                jboolean c = ctx->env->CallBooleanMethod(ctx->cb, ctx->isCancelled);
                if (c == JNI_TRUE) { ctx->cancelled = true; return -1; }
            }
            return 1;
        }
        case UCM_NEEDPASSWORD:
        case UCM_NEEDPASSWORDW: {
            // Password requested mid-stream — happens when archive has encrypted
            // headers OR encrypted files but we didn't pre-set via RARSetPassword.
            if (!ctx->password.empty()) {
                if (msg == UCM_NEEDPASSWORD) {
                    auto* dest = reinterpret_cast<char*>(p1);
                    int size = static_cast<int>(p2);
                    std::strncpy(dest, ctx->password.c_str(), size);
                    dest[size - 1] = '\0';
                } else {
                    auto* destW = reinterpret_cast<wchar_t*>(p1);
                    int size = static_cast<int>(p2);
                    for (int i = 0; i < size - 1 && i < (int)ctx->password.size(); ++i) {
                        destW[i] = static_cast<wchar_t>(ctx->password[i]);
                    }
                    destW[std::min((int)ctx->password.size(), size - 1)] = L'\0';
                }
                return 1;
            }
            ctx->needPasswordReported = true;
            return -1;  // abort
        }
        default:
            return 0;
    }
}

void throwException(JNIEnv* env, const char* className, const char* msg) {
    jclass clazz = env->FindClass(className);
    if (clazz) env->ThrowNew(clazz, msg);
}

std::string utf16_to_utf8(const wchar_t* w) {
    // Android wchar_t is 32-bit. Hand-roll a minimal UTF-8 encoder so we can
    // round-trip filenames (which unrar reports as wide chars).
    std::string out;
    if (!w) return out;
    for (; *w; ++w) {
        uint32_t c = static_cast<uint32_t>(*w);
        if (c < 0x80) {
            out.push_back(static_cast<char>(c));
        } else if (c < 0x800) {
            out.push_back(static_cast<char>(0xC0 | (c >> 6)));
            out.push_back(static_cast<char>(0x80 | (c & 0x3F)));
        } else if (c < 0x10000) {
            out.push_back(static_cast<char>(0xE0 | (c >> 12)));
            out.push_back(static_cast<char>(0x80 | ((c >> 6) & 0x3F)));
            out.push_back(static_cast<char>(0x80 | (c & 0x3F)));
        } else {
            out.push_back(static_cast<char>(0xF0 | (c >> 18)));
            out.push_back(static_cast<char>(0x80 | ((c >> 12) & 0x3F)));
            out.push_back(static_cast<char>(0x80 | ((c >> 6) & 0x3F)));
            out.push_back(static_cast<char>(0x80 | (c & 0x3F)));
        }
    }
    return out;
}

std::wstring utf8_to_utf16(const char* s) {
    std::wstring out;
    if (!s) return out;
    while (*s) {
        uint32_t c = static_cast<uint8_t>(*s++);
        if (c < 0x80) {
            out.push_back(static_cast<wchar_t>(c));
        } else if ((c & 0xE0) == 0xC0) {
            uint32_t c2 = static_cast<uint8_t>(*s++);
            out.push_back(static_cast<wchar_t>(((c & 0x1F) << 6) | (c2 & 0x3F)));
        } else if ((c & 0xF0) == 0xE0) {
            uint32_t c2 = static_cast<uint8_t>(*s++);
            uint32_t c3 = static_cast<uint8_t>(*s++);
            out.push_back(static_cast<wchar_t>(((c & 0x0F) << 12) | ((c2 & 0x3F) << 6) | (c3 & 0x3F)));
        } else if ((c & 0xF8) == 0xF0) {
            uint32_t c2 = static_cast<uint8_t>(*s++);
            uint32_t c3 = static_cast<uint8_t>(*s++);
            uint32_t c4 = static_cast<uint8_t>(*s++);
            out.push_back(static_cast<wchar_t>(((c & 0x07) << 18) | ((c2 & 0x3F) << 12) |
                                                ((c3 & 0x3F) << 6) | (c4 & 0x3F)));
        }
    }
    return out;
}

const char* errorString(int code) {
    switch (code) {
        case 0:                       return "OK";
        case ERAR_END_ARCHIVE:        return "End of archive";
        case ERAR_NO_MEMORY:          return "Out of memory";
        case ERAR_BAD_DATA:           return "Bad data (CRC failure)";
        case ERAR_BAD_ARCHIVE:        return "Bad archive";
        case ERAR_UNKNOWN_FORMAT:     return "Unknown format";
        case ERAR_EOPEN:              return "Cannot open file";
        case ERAR_ECREATE:            return "Cannot create file";
        case ERAR_ECLOSE:             return "Cannot close file";
        case ERAR_EREAD:              return "Read error";
        case ERAR_EWRITE:             return "Write error";
        case ERAR_SMALL_BUF:          return "Buffer too small";
        case ERAR_UNKNOWN:            return "Unknown error";
        case ERAR_MISSING_PASSWORD:   return "Password required";
        case ERAR_EREFERENCE:         return "Bad reference";
        case ERAR_BAD_PASSWORD:       return "Bad password";
        case ERAR_LARGE_DICT:         return "Dictionary too large";
        default:                      return "Unrar error";
    }
}

}  // namespace

extern "C"
JNIEXPORT jobject JNICALL
Java_com_example_f95updater_UnrarNative_listEntriesNative(
        JNIEnv* env, jclass /*cls*/, jstring jPath, jstring jPassword) {

    const char* pathC = env->GetStringUTFChars(jPath, nullptr);
    std::string path(pathC ? pathC : "");
    env->ReleaseStringUTFChars(jPath, pathC);

    std::string password;
    if (jPassword) {
        const char* pwC = env->GetStringUTFChars(jPassword, nullptr);
        if (pwC) { password = pwC; env->ReleaseStringUTFChars(jPassword, pwC); }
    }

    RAROpenArchiveDataEx openData = {};
    std::wstring wpath = utf8_to_utf16(path.c_str());
    openData.ArcNameW = const_cast<wchar_t*>(wpath.c_str());
    openData.OpenMode = RAR_OM_LIST;

    HANDLE h = RAROpenArchiveEx(&openData);
    if (!h || openData.OpenResult != 0) {
        throwException(env, "java/io/IOException", errorString(openData.OpenResult));
        return nullptr;
    }
    if (!password.empty()) {
        RARSetPassword(h, const_cast<char*>(password.c_str()));
    }

    jclass arrayListCls = env->FindClass("java/util/ArrayList");
    jmethodID ctor = env->GetMethodID(arrayListCls, "<init>", "()V");
    jmethodID add  = env->GetMethodID(arrayListCls, "add", "(Ljava/lang/Object;)Z");
    jobject result = env->NewObject(arrayListCls, ctor);

    jclass entryCls = env->FindClass("com/example/f95updater/UnrarEntry");
    jmethodID entryCtor = env->GetMethodID(entryCls, "<init>", "(Ljava/lang/String;JZ)V");

    RARHeaderDataEx hd = {};
    int rc;
    while ((rc = RARReadHeaderEx(h, &hd)) == 0) {
        std::string fname = utf16_to_utf8(hd.FileNameW);
        bool isDir = (hd.Flags & RHDF_DIRECTORY) != 0;
        jlong size = static_cast<jlong>(hd.UnpSize) |
                     (static_cast<jlong>(hd.UnpSizeHigh) << 32);
        jstring jname = env->NewStringUTF(fname.c_str());
        jobject ent = env->NewObject(entryCls, entryCtor, jname, size, (jboolean) isDir);
        env->CallBooleanMethod(result, add, ent);
        env->DeleteLocalRef(ent);
        env->DeleteLocalRef(jname);

        int prc = RARProcessFile(h, RAR_SKIP, nullptr, nullptr);
        if (prc != 0) {
            RARCloseArchive(h);
            throwException(env, "java/io/IOException", errorString(prc));
            return nullptr;
        }
    }
    RARCloseArchive(h);
    if (rc != ERAR_END_ARCHIVE && rc != 0) {
        throwException(env, "java/io/IOException", errorString(rc));
        return nullptr;
    }
    return result;
}

extern "C"
JNIEXPORT void JNICALL
Java_com_example_f95updater_UnrarNative_extractAllNative(
        JNIEnv* env, jclass /*cls*/, jstring jPath, jstring jPassword,
        jstring jDestDir, jobject cb) {

    const char* pathC = env->GetStringUTFChars(jPath, nullptr);
    std::string path(pathC ? pathC : "");
    env->ReleaseStringUTFChars(jPath, pathC);

    const char* destC = env->GetStringUTFChars(jDestDir, nullptr);
    std::string destDir(destC ? destC : "");
    env->ReleaseStringUTFChars(jDestDir, destC);

    std::string password;
    if (jPassword) {
        const char* pwC = env->GetStringUTFChars(jPassword, nullptr);
        if (pwC) { password = pwC; env->ReleaseStringUTFChars(jPassword, pwC); }
    }

    CallbackContext ctx;
    ctx.env = env;
    ctx.cb  = cb;
    ctx.password = password;
    if (cb) {
        jclass cbCls = env->GetObjectClass(cb);
        ctx.onChunk     = env->GetMethodID(cbCls, "onChunk", "(JLjava/lang/String;)V");
        ctx.isCancelled = env->GetMethodID(cbCls, "isCancelled", "()Z");
    }

    RAROpenArchiveDataEx openData = {};
    std::wstring wpath = utf8_to_utf16(path.c_str());
    openData.ArcNameW = const_cast<wchar_t*>(wpath.c_str());
    openData.OpenMode = RAR_OM_EXTRACT;
    openData.Callback = UnrarCallback;
    openData.UserData = reinterpret_cast<LPARAM>(&ctx);

    HANDLE h = RAROpenArchiveEx(&openData);
    if (!h || openData.OpenResult != 0) {
        if (openData.OpenResult == ERAR_MISSING_PASSWORD || ctx.needPasswordReported) {
            throwException(env, "com/example/f95updater/UnrarPasswordRequiredException",
                           "Password required for this archive.");
        } else {
            throwException(env, "java/io/IOException", errorString(openData.OpenResult));
        }
        return;
    }
    if (!password.empty()) {
        RARSetPassword(h, const_cast<char*>(password.c_str()));
    }

    std::wstring wdest = utf8_to_utf16(destDir.c_str());

    RARHeaderDataEx hd = {};
    int rc;
    while ((rc = RARReadHeaderEx(h, &hd)) == 0) {
        // Report current entry name (one-shot at start of each file).
        if (ctx.cb && ctx.onChunk) {
            std::string fname = utf16_to_utf8(hd.FileNameW);
            jstring jname = env->NewStringUTF(fname.c_str());
            env->CallVoidMethod(ctx.cb, ctx.onChunk, (jlong) 0, jname);
            env->DeleteLocalRef(jname);
            if (env->ExceptionCheck()) {
                env->ExceptionClear();
                RARCloseArchive(h);
                return;
            }
        }
        int prc = RARProcessFileW(h, RAR_EXTRACT,
                                  const_cast<wchar_t*>(wdest.c_str()), nullptr);
        if (prc != 0) {
            RARCloseArchive(h);
            if (prc == ERAR_MISSING_PASSWORD || prc == ERAR_BAD_PASSWORD ||
                ctx.needPasswordReported) {
                throwException(env, "com/example/f95updater/UnrarPasswordRequiredException",
                               errorString(prc));
            } else if (ctx.cancelled) {
                throwException(env, "java/lang/InterruptedException", "Cancelled by user");
            } else {
                throwException(env, "java/io/IOException", errorString(prc));
            }
            return;
        }
    }
    RARCloseArchive(h);
    if (rc != ERAR_END_ARCHIVE && rc != 0) {
        throwException(env, "java/io/IOException", errorString(rc));
    }
}
