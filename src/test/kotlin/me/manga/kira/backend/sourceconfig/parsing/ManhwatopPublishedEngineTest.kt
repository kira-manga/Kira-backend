package me.manga.kira.backend.sourceconfig.parsing

import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.runBlocking
import me.manga.kira.source.contracts.SourceChapterRef
import me.manga.kira.source.contracts.SourceEngineError
import me.manga.kira.source.contracts.SourceEngineResult
import me.manga.kira.source.contracts.SourceHeaderProvider
import me.manga.kira.source.contracts.SourceHttpMethod
import me.manga.kira.source.contracts.SourceMangaRef
import me.manga.kira.source.contracts.SourceRequest
import me.manga.kira.source.contracts.SourceResponse
import me.manga.kira.source.contracts.SourceTransport
import me.manga.kira.source.contracts.model.SourceConfigDocument
import me.manga.kira.source.engine.DefaultSourceConfigValidator
import me.manga.kira.source.engine.DefaultStrategyRegistry
import me.manga.kira.source.engine.GenericSourceEngine
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import me.manga.kira.source.contracts.SourceConfigParser as SharedSourceConfigParser

/**
 * Runs the unchanged authoring file with the normal published engine dependency (currently 0.1.0).
 * All response HTML is synthetic; no captured provider payloads, network, or credentials are used.
 * This is execution compatibility coverage, not live-site or publication approval.
 */
class ManhwatopPublishedEngineTest {
    private val base = "https://manhwatop.com"
    private val images = "https://images.example.invalid"
    private val mangaUrl = "$base/manga/fixture-alpha/"
    private val chapterUrl = "${mangaUrl}chapter-1/"

    @Test
    fun `authored Manhwatop executes discovery details and free reader on the published engine`() = runBlocking {
        val source = SharedSourceConfigParser.parseSource(
            Files.readString(Path.of("config", "sources", "Manhwatop.json")),
        ).valueOrFail()
        assertEquals("Manhwatop", source.api)
        assertEquals("(EN)", source.language)
        assertEquals(base, source.baseUrl)
        assertFalse(source.enabled, "Executing an authoring input must not activate it")
        assertFalse(source.usesCapturedHeaders)
        val validation = DefaultSourceConfigValidator(DefaultStrategyRegistry()).validate(
            SourceConfigDocument(schemaVersion = 1, sources = listOf(source)),
        )
        assertTrue(validation.isValid, validation.errors.joinToString())

        val recent = """
            <div class="list-chapter">
              <div class="chapter-item"><a href="/manga/fixture-alpha/chapter-12-5/"> Chapter 12.5 </a></div>
              <div class="chapter-item"><a href="#">Chapter 0</a></div>
            </div>
        """.trimIndent()
        val exchanges = listOf(
            Exchange(
                archiveRequest("0", "_latest_update"),
                archiveCard("fixture-alpha", "Fixture Alpha", "data-src='$images/alpha.jpg' src='/placeholder.jpg'", recent) +
                    archiveCard("fixture-beta", "Fixture Beta", "data-src='' data-lazy-src='$images/beta.jpg' src='/placeholder.jpg'"),
            ),
            Exchange(archiveRequest("1", "_latest_update"), archiveCard("fixture-gamma", "Fixture Gamma", "src='$images/gamma.jpg'")),
            Exchange(archiveRequest("0", "_wp_manga_views"), archiveCard("fixture-featured", "Fixture Featured", "data-src='$images/featured.jpg'")),
            Exchange(
                SourceRequest("$base/?s=mage%20%26%20%E9%9B%AA%2F%2B&post_type=wp-manga&paged=1"),
                searchCard("fixture-search-one", "Search One", "data-src='' data-lazy-src='$images/search-one.jpg' src='/placeholder.jpg'", "Action") +
                    searchCard("fixture-restricted", "Restricted Fixture", "src='$images/restricted.jpg'", "yAoI"),
            ),
            Exchange(
                SourceRequest("$base/?s=mage%20%26%20%E9%9B%AA%2F%2B&post_type=wp-manga&paged=2"),
                searchCard("fixture-search-two", "Search Two", "data-original='$images/search-two.jpg' src='/placeholder.jpg'", "Adventure"),
            ),
            Exchange(SourceRequest(mangaUrl), detailsHtml),
            Exchange(chaptersRequest(), chaptersHtml),
            Exchange(SourceRequest(chapterUrl), pagesHtml),
        )
        val transport = SyntheticTransport(exchanges)
        val headers = SourceHeaderProvider { error("This source must never read captured credentials") }
        val engine = GenericSourceEngine(source, transport, headers)

        val first = engine.home(1).valueOrFail()
        val second = engine.home(2).valueOrFail()
        assertEquals(listOf("Fixture Alpha", "Fixture Beta"), first.map { it.title })
        assertEquals(listOf(mangaUrl, "$base/manga/fixture-beta/"), first.map { it.url })
        assertEquals(listOf("$images/alpha.jpg", "$images/beta.jpg"), first.map { it.coverUrl })
        assertEquals(listOf(4, 4), first.map { it.rating })
        assertEquals(listOf(SourceChapterRef("12.5", "${mangaUrl}chapter-12-5/")), first.first().recentChapters)
        assertEquals(listOf("$base/manga/fixture-gamma/"), second.map { it.url })
        assertEquals("$images/gamma.jpg", second.single().coverUrl)
        assertTrue(first.map { it.url }.intersect(second.map { it.url }.toSet()).isEmpty())
        val featured = engine.featured(1).valueOrFail().single()
        assertEquals("Fixture Featured", featured.title)
        assertEquals("$base/manga/fixture-featured/", featured.url)
        assertEquals("$images/featured.jpg", featured.coverUrl)

        val searchFirst = engine.search("mage & 雪/+", 1).valueOrFail().single()
        val searchSecond = engine.search("mage & 雪/+", 2).valueOrFail().single()
        assertEquals("Search One", searchFirst.title)
        assertEquals("$base/manga/fixture-search-one/", searchFirst.url)
        assertEquals("$images/search-one.jpg", searchFirst.coverUrl)
        assertEquals(listOf("Action"), searchFirst.genres)
        assertEquals(3, searchFirst.rating)
        assertEquals("Search Two", searchSecond.title)
        assertEquals("$base/manga/fixture-search-two/", searchSecond.url)
        assertEquals("$images/search-two.jpg", searchSecond.coverUrl)
        assertEquals(listOf("Adventure"), searchSecond.genres)

        val manga = SourceMangaRef(source.api, source.language, "Fallback title", mangaUrl, "")
        val details = engine.details(manga).valueOrFail()
        assertEquals(source.api, details.api)
        assertEquals(source.language, details.language)
        assertEquals(mangaUrl, details.url)
        assertEquals("Fixture Alpha", details.title)
        assertEquals("$images/detail.jpg", details.coverUrl)
        assertEquals("Synthetic description.", details.description)
        assertEquals("Example Author", details.author)
        assertEquals("4.5", details.rating)
        assertEquals("Ongoing", details.status)
        assertEquals(listOf("Action", "Adventure"), details.genres)
        // Preserve provider order/duplicate URL identities; premium and placeholder rows are excluded.
        assertEquals(listOf("12.5", "12.5", "1"), details.chapters.map { it.number })
        assertEquals(listOf("Chapter 12.5", "Chapter 12.5", "Chapter 1"), details.chapters.map { it.name })
        assertEquals(listOf("${mangaUrl}chapter-12-5/", "${mangaUrl}chapter-12-5/", chapterUrl), details.chapters.map { it.url })
        assertTrue(details.chapters.all { it.date == null }, "No publication date is fabricated")
        val pages = engine.pages(manga, details.chapters.last()).valueOrFail()
        assertEquals((1..4).map { "$images/page-$it.jpg" }, pages.map { it.url })
        assertTrue(pages.all { it.headers.isEmpty() }, "No credentials may be attached to image results")
        transport.assertConsumed()

        val failedChapters = SyntheticTransport(
            listOf(Exchange(SourceRequest(mangaUrl), detailsHtml), Exchange(chaptersRequest(), "unavailable", 503)),
        )
        val failure = GenericSourceEngine(source, failedChapters, headers).details(manga)
        assertEquals(SourceEngineResult.Failure(SourceEngineError.Http(503)), failure)
        failedChapters.assertConsumed()
    }

    private fun archiveRequest(page: String, sort: String) = SourceRequest(
        url = "$base/wp-admin/admin-ajax.php",
        method = SourceHttpMethod.POST_FORM,
        formBody = listOf(
            "action" to "madara_load_more",
            "page" to page,
            "template" to "madara-core/content/content-archive",
            "vars[orderby]" to "meta_value_num",
            "vars[paged]" to "1",
            "vars[posts_per_page]" to "20",
            "vars[post_type]" to "wp-manga",
            "vars[post_status]" to "publish",
            "vars[meta_key]" to sort,
            "vars[order]" to "desc",
            "vars[sidebar]" to "right",
            "vars[manga_archives_item_layout]" to "big_thumbnail",
        ),
    )

    private fun chaptersRequest() = SourceRequest(
        url = "${mangaUrl}ajax/chapters",
        method = SourceHttpMethod.POST_FORM,
        formBody = emptyList(),
    )

    private fun archiveCard(slug: String, title: String, attributes: String, recent: String = "") = """
        <div class="page-item-detail">
          <div class="item-thumb"><img $attributes></div>
          <div class="item-summary"><div class="post-title"><a href="/manga/$slug/"> $title </a></div>
            <span class="total_votes">4.9</span>$recent</div>
        </div>
    """.trimIndent()

    private fun searchCard(slug: String, title: String, attributes: String, genre: String) = """
        <div class="row c-tabs-item__content">
          <div class="tab-thumb"><a><img $attributes></a></div>
          <div class="post-title"><h2 class="h5"><a href="/manga/$slug/"> $title </a></h2></div>
          <span class="total_votes">3.6</span>
          <div class="post-content_item mg_genres"><div class="summary-content"><a> $genre </a></div></div>
        </div>
    """.trimIndent()

    private val detailsHtml get() = """
        <div class="post-title"><h1> Fixture Alpha </h1></div>
        <div class="summary_image"><img data-original="$images/detail.jpg" src="/placeholder.jpg"></div>
        <div class="post-total-rating"><span class="score">4.5</span></div>
        <div class="description-summary"><div class="summary__content"> Synthetic description. </div></div>
        <div class="author-content"> Example Author </div><div class="summary-content mg_status"> Ongoing </div>
        <div class="genres-content"><a>Action</a><a>Adventure</a></div>
    """.trimIndent()

    private val chaptersHtml = """
        <ul class="main version-chap">
          <li class="wp-manga-chapter"><a href="/manga/fixture-alpha/chapter-12-5/"> Chapter 12.5 </a></li>
          <li class="wp-manga-chapter premium-block"><a href="/manga/fixture-alpha/paid-13/">Chapter 13</a></li>
          <li class="wp-manga-chapter"><a href="/manga/fixture-alpha/chapter-12-5/">Chapter 12.5</a></li>
          <li class="wp-manga-chapter"><a href="#">Chapter 0</a></li>
          <li class="wp-manga-chapter"><a>Chapter 0</a></li>
          <li class="wp-manga-chapter"><a href="/manga/fixture-alpha/chapter-1/">Chapter 1</a></li>
        </ul>
    """.trimIndent()

    private val pagesHtml get() = """
        <div class="reading-content">
          <img class="wp-manga-chapter-img" data-src=" $images/page-1.jpg " src="/placeholder.jpg">
          <img class="wp-manga-chapter-img" data-src="" data-lazy-src="$images/page-2.jpg" src="/placeholder.jpg">
          <img class="wp-manga-chapter-img" data-original="$images/page-3.jpg" src="/placeholder.jpg">
          <img class="wp-manga-chapter-img" src="$images/page-4.jpg">
          <img class="wp-manga-chapter-img" data-src="" src="">
          <img class="advertisement" src="$images/not-a-page.jpg">
        </div>
    """.trimIndent()

    private data class Exchange(val request: SourceRequest, val body: String, val status: Int = 200)

    private class SyntheticTransport(private val exchanges: List<Exchange>) : SourceTransport {
        private val requests = mutableListOf<SourceRequest>()

        override suspend fun execute(request: SourceRequest): SourceResponse {
            val exchange = exchanges.getOrNull(requests.size) ?: error("Unexpected extra synthetic request")
            requests += request
            return SourceResponse(exchange.status, exchange.body, finalUrl = request.url)
        }

        fun assertConsumed() {
            // Compare outside the engine failure boundary so a request mismatch retains its full assertion.
            assertEquals(exchanges.map { it.request }, requests)
        }
    }

    private fun <T> SourceEngineResult<T>.valueOrFail(): T = when (this) {
        is SourceEngineResult.Success -> value
        is SourceEngineResult.Failure -> error("Expected successful synthetic extraction, received $error")
    }
}
