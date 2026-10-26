package id.gerry.gbook

/** Bottom navigation destination of the app. Faithful v0.3.0: no nav-compose. */
enum class Tab { Library, Reader, Stats }

/** Library presentation toggle. */
enum class LibraryView { Grid, List }

/** User-selectable library sorting. Requirement 7c. */
enum class BookSort(val label: String, val storage: String) {
    Recent("Most Recently Read", "recent"),
    Title("A \u2192 Z", "title")
}
