package com.leovonix.youtube

import android.content.Context
import com.stleo.api.Log
import com.stleo.ott.Actor
import com.stleo.ott.ActorData
import com.stleo.ott.HomePageList
import com.stleo.ott.HomePageResponse
import com.stleo.ott.LoadResponse
import com.stleo.ott.MainAPI
import com.stleo.ott.SearchResponse
import com.stleo.ott.SearchResponseList
import com.stleo.ott.SubtitleFile
import com.stleo.ott.TvType
import com.stleo.ott.mainPageOf
import com.stleo.ott.newEpisode
import com.stleo.ott.newHomePageResponse
import com.stleo.ott.newMovieLoadResponse
import com.stleo.ott.newMovieSearchResponse
import com.stleo.ott.newSearchResponseList
import com.stleo.ott.newTvSeriesLoadResponse
import com.stleo.ott.plugins.LeoPlugin
import com.stleo.ott.plugins.Plugin
import com.stleo.ott.utils.ExtractorLink
import com.stleo.ott.utils.loadExtractor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.schabi.newpipe.extractor.InfoItem
import org.schabi.newpipe.extractor.Page
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.kiosk.KioskExtractor
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamType
import java.util.concurrent.ConcurrentHashMap

@LeoPlugin
class YoutubePlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(YoutubeProvider())
    }
}

class YoutubeProvider : MainAPI() {
    override var mainUrl = "https://www.youtube.com"
    override var name = "YouTube"
    override var lang = "en"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.Others, TvType.Live, TvType.TvSeries)

    override val mainPage = mainPageOf(
        "Trending" to "Trending",
        "trending_movies_and_shows" to "Movies & Shows",
        "trending_music" to "Music",
        "trending_gaming" to "Gaming",
        "trending_podcasts_episodes" to "Podcasts",
        "live" to "Live",
    )

    private val pageCache = ConcurrentHashMap<String, Page>()
    private val searchPageCache = ConcurrentHashMap<String, Page>()

    private fun getKioskExtractor(kioskId: String): KioskExtractor<out InfoItem> {
        val kioskList = ServiceList.YouTube.kioskList
        return if (kioskId.isBlank()) {
            kioskList.defaultKioskExtractor
        } else {
            runCatching { kioskList.getExtractorById(kioskId, null) }
                .getOrElse { kioskList.defaultKioskExtractor }
        }
    }

    private fun InfoItem.toSearchResponse(): SearchResponse {
        val title = name ?: "Unknown"
        val itemUrl = url ?: ""
        return newMovieSearchResponse(title, itemUrl, TvType.Others) {
            posterUrl = thumbnails.lastOrNull()?.url
        }
    }

    override suspend fun getMainPage(page: Int, request: com.stleo.ott.MainPageRequest): HomePageResponse {
        return withContext(Dispatchers.IO) {
            try {
                val key = request.data
                if (page <= 1) pageCache.remove(key)

                val extractor = getKioskExtractor(key)
                val pageData = if (page <= 1) {
                    extractor.fetchPage()
                    extractor.initialPage
                } else {
                    val next = pageCache[key] ?: return@withContext newHomePageResponse(emptyList<HomePageList>(), false)
                    extractor.getPage(next)
                }

                val nextPage = pageData.nextPage
                if (nextPage != null) pageCache[key] = nextPage else pageCache.remove(key)

                val results = pageData.items.map { it.toSearchResponse() }
                val headerName = runCatching { extractor.name }.getOrNull().orEmpty().ifBlank { request.name }.ifBlank { "Trending" }
                newHomePageResponse(listOf(HomePageList(headerName, results, isHorizontalImages = true)), pageData.hasNextPage())
            } catch (e: Throwable) {
                Log.e("YoutubeProvider", "getMainPage failed: ${e.message}")
                newHomePageResponse(emptyList<HomePageList>(), false)
            }
        }
    }

    override suspend fun search(query: String, page: Int): SearchResponseList? {
        return withContext(Dispatchers.IO) {
            try {
                if (page <= 1) searchPageCache.remove(query)
                val extractor = ServiceList.YouTube.getSearchExtractor(query)
                val pageData = if (page <= 1) {
                    extractor.fetchPage()
                    extractor.initialPage
                } else {
                    val next = searchPageCache[query] ?: return@withContext newSearchResponseList(emptyList(), false)
                    extractor.getPage(next)
                }

                val nextPage = pageData.nextPage
                if (nextPage != null) searchPageCache[query] = nextPage else searchPageCache.remove(query)

                val results = pageData.items.map { it.toSearchResponse() }
                newSearchResponseList(results, pageData.hasNextPage())
            } catch (e: Throwable) {
                Log.e("YoutubeProvider", "search failed: ${e.message}")
                newSearchResponseList(emptyList(), false)
            }
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        return when {
            url.contains("/watch?v=") || url.contains("youtu.be/") || url.contains("/shorts/") -> loadVideo(url)
            url.contains("/playlist?list=") -> loadPlaylist(url)
            url.contains("/channel/") || url.contains("/@") || url.contains("/c/") -> loadChannel(url)
            else -> runCatching { loadVideo(url) }.getOrNull()
        }
    }

    private suspend fun loadVideo(url: String): LoadResponse {
        val info = withContext(Dispatchers.IO) {
            val extractor = ServiceList.YouTube.getStreamExtractor(url)
            extractor.fetchPage()
            StreamInfo.getInfo(extractor)
        }
        val isLive = info.streamType?.name?.contains("LIVE", ignoreCase = true) == true
        return newMovieLoadResponse(
            name = info.name ?: "YouTube Video",
            url = url,
            type = if (isLive) TvType.Live else TvType.Others,
            dataUrl = url,
        ) {
            plot = info.description?.content
            posterUrl = info.thumbnails.lastOrNull()?.url
            duration = info.duration.toInt().takeIf { it > 0 }
            val uploader = info.uploaderName?.takeIf { it.isNotBlank() }
            if (uploader != null) {
                val avatar = info.uploaderAvatars.lastOrNull()?.url
                actors = listOf(ActorData(Actor(uploader, avatar)))
            }
            tags = info.tags?.take(5)
        }
    }

    private suspend fun loadChannel(url: String): LoadResponse {
        val (channelName, channelDesc, channelBanner, channelAvatar, episodes) = withContext(Dispatchers.IO) {
            val extractor = ServiceList.YouTube.getChannelExtractor(url)
            extractor.fetchPage()
            val cName = extractor.name ?: "Channel"
            val cDesc = extractor.description
            val cAvatar = extractor.avatars.lastOrNull()?.url
            val cBanner = extractor.banners.lastOrNull()?.url
            val tabs = extractor.tabs
            val videosTab = tabs.firstOrNull { it.url.contains("/videos") } ?: tabs.firstOrNull()
                ?: throw IllegalStateException("No videos tab found")

            val tabExtractor = ServiceList.YouTube.getChannelTabExtractor(videosTab)
            val eps = mutableListOf<com.stleo.ott.Episode>()
            var page = tabExtractor.initialPage
            eps += page.items.mapNotNull { item ->
                val itemUrl = item.url ?: return@mapNotNull null
                newEpisode(itemUrl) {
                    name = item.name
                    posterUrl = item.thumbnails.lastOrNull()?.url
                }
            }
            var loaded = 1
            while (page.hasNextPage() && loaded < 5) {
                val next = page.nextPage ?: break
                page = tabExtractor.getPage(next)
                eps += page.items.mapNotNull { item ->
                    val itemUrl = item.url ?: return@mapNotNull null
                    newEpisode(itemUrl) {
                        name = item.name
                        posterUrl = item.thumbnails.lastOrNull()?.url
                    }
                }
                loaded++
            }
            ChannelPayload(cName, cDesc, cBanner, cAvatar, eps)
        }

        return newTvSeriesLoadResponse(channelName, url, TvType.TvSeries, episodes) {
            plot = channelDesc
            posterUrl = channelBanner
            backgroundPosterUrl = channelBanner
            tags = listOf("Channel")
            actors = listOf(ActorData(Actor(channelName, channelAvatar)))
        }
    }

    private suspend fun loadPlaylist(url: String): LoadResponse {
        val payload = withContext(Dispatchers.IO) {
            val extractor = ServiceList.YouTube.getPlaylistExtractor(url)
            extractor.fetchPage()
            val pName = extractor.name ?: "Playlist"
            val pDesc = extractor.description?.content
            val pThumb = extractor.thumbnails.lastOrNull()?.url
            val uploader = extractor.uploaderName.orEmpty()
            val uploaderAvatar = extractor.uploaderAvatars.lastOrNull()?.url
            val eps = mutableListOf<com.stleo.ott.Episode>()
            var page = extractor.initialPage
            eps += page.items.mapNotNull { item ->
                val itemUrl = item.url ?: return@mapNotNull null
                newEpisode(itemUrl) {
                    name = item.name
                    posterUrl = item.thumbnails.lastOrNull()?.url
                }
            }
            var loaded = 1
            while (page.hasNextPage() && loaded < 5) {
                val next = page.nextPage ?: break
                page = extractor.getPage(next)
                eps += page.items.mapNotNull { item ->
                    val itemUrl = item.url ?: return@mapNotNull null
                    newEpisode(itemUrl) {
                        name = item.name
                        posterUrl = item.thumbnails.lastOrNull()?.url
                    }
                }
                loaded++
            }
            PlaylistPayload(pName, pDesc, pThumb, uploader, uploaderAvatar, eps)
        }

        return newTvSeriesLoadResponse(payload.name, url, TvType.TvSeries, payload.episodes) {
            plot = payload.description
            posterUrl = payload.thumbnail
            tags = listOf(if (payload.uploader.isNotBlank()) "Channel: ${payload.uploader}" else "Playlist")
            if (payload.uploader.isNotBlank()) {
                actors = listOf(ActorData(Actor(payload.uploader, payload.uploaderAvatar)))
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val watchUrl = if (data.startsWith("http", ignoreCase = true)) {
            data
        } else {
            "https://www.youtube.com/watch?v=$data"
        }
        return loadExtractor(watchUrl, subtitleCallback, callback)
    }

    private data class ChannelPayload(
        val name: String,
        val description: String?,
        val banner: String?,
        val avatar: String?,
        val episodes: List<com.stleo.ott.Episode>,
    )

    private data class PlaylistPayload(
        val name: String,
        val description: String?,
        val thumbnail: String?,
        val uploader: String,
        val uploaderAvatar: String?,
        val episodes: List<com.stleo.ott.Episode>,
    )
}
