package com.example.f95updater

enum class SortKey(val label: String) {
    Name("Name"),
    Installed("Install date"),
    AppUpdated("App update"),
    LastUsed("Last used"),
    ThreadUpdated("Thread updated"),
    Size("Total size"),
    AppSize("App size"),
    DataSize("Data size"),
    CacheSize("Cache size"),
    Status("Update status"),
}

enum class LibraryLayoutMode(val label: String) {
    List("List"),
    Cards("Cards"),
    FolderTree("Folder tree"),
    ;

    fun next(): LibraryLayoutMode = entries[(ordinal + 1) % entries.size]
}

enum class Tab { Installed, Catalog }
