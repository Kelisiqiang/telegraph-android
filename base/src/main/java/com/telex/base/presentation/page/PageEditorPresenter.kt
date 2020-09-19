package com.telex.base.presentation.page

import com.telex.base.R
import com.telex.base.model.interactors.PageInteractor
import com.telex.base.model.source.local.entity.Page
import com.telex.base.model.source.remote.data.NodeElementData
import com.telex.base.presentation.base.BasePresenter
import com.telex.base.presentation.base.ErrorHandler
import com.telex.base.presentation.page.adapter.ImageUploadStatus
import com.telex.base.presentation.page.format.Format
import com.telex.base.presentation.page.format.FormatType
import com.telex.base.presentation.page.format.ImageFormat
import com.telex.base.presentation.page.format.MediaFormat
import com.telex.base.presentation.page.format.VideoFormat
import com.telex.base.utils.TelegraphContentConverter
import io.reactivex.Completable
import io.reactivex.Observable
import io.reactivex.Single
import io.reactivex.android.schedulers.AndroidSchedulers
import io.reactivex.schedulers.Schedulers
import io.reactivex.subjects.BehaviorSubject
import moxy.InjectViewState
import timber.log.Timber
import java.util.concurrent.TimeUnit
import javax.inject.Inject

/**
 * @author Sergey Petrov
 */
@InjectViewState
class PageEditorPresenter @Inject constructor(
        private val pageInteractor: PageInteractor,
        private val telegraphContentConverter: TelegraphContentConverter,
        errorHandler: ErrorHandler
) : BasePresenter<PageEditorView>(errorHandler) {

    private var page: Page? = null
    private var draftChangesObserver: BehaviorSubject<DraftFields>? = null
    var isDraftNeeded = false

    fun openPage(pageId: Long?) {
        val observable: Observable<Page> =
                if (pageId == null) {
                    pageInteractor.getPageOrCreateDraft(pageId).toObservable()
                } else {
                    Observable.concat(
                            pageInteractor.getCachedPage(pageId).toObservable(),
                            pageInteractor.getPageOrCreateDraft(pageId).toObservable()
                                    .delay(350, TimeUnit.MILLISECONDS)
                    )
                }

        observable
                .subscribeOn(Schedulers.io())
                .observeOn(Schedulers.computation())
                .map { page -> Pair(page, convertNodes(page.nodes.content)) }
                .observeOn(AndroidSchedulers.mainThread(), true)
                .doOnSubscribe {
                    if (page?.nodes?.content.isNullOrEmpty()) {
                        viewState.showContentProgress(true)
                    }
                }
                .doOnNext { result ->
                    page = result.first
                    if (!page?.nodes?.content.isNullOrEmpty()) {
                        viewState.showContentProgress(false)
                    }
                    viewState.showPage(page = result.first, formats = result.second)
                }
                .doOnComplete {
                    isDraftNeeded = true
                }
                .doAfterTerminate { viewState.showContentProgress(false) }
                .compositeSubscribe()
    }

    fun onMoreClicked() {
        if (page != null) {
            viewState.showMore(requireNotNull(page))
        }
    }

    fun publishPage(title: String, authorName: String?, authorUrl: String?, formats: List<Format>) {
        if (hasNotUploadedImages(formats)) {
            viewState.showError(R.string.upload_in_progress_error)
        } else {
            val page = page ?: throw IllegalArgumentException("page can't be null")

            convertFormatsObservable(formats)
                    .flatMapCompletable { nodes ->
                        pageInteractor.publishPage(page.id, page.path, title, authorName, authorUrl, nodes, getPageImageUrl(formats))
                    }
                    .doOnSubscribe { viewState.showProgress(true) }
                    .doAfterTerminate { viewState.showProgress(false) }
                    .compositeSubscribe(
                            onSuccess = {
                                isDraftNeeded = false
                                viewState.onPageSaved()
                            }
                    )
        }
    }

    fun onDraftChanged(draftFields: DraftFields) {
        if (isDraftNeeded && draftChangesObserver == null) {
            val observer = BehaviorSubject.create<DraftFields>()
            draftChangesObserver = observer
            observer.debounce(1, TimeUnit.SECONDS)
                    .flatMapCompletable { result -> savePageDraftIfNeededCompletable(result, false) }
                    .justSubscribe(onError = object : OnErrorConsumer() {
                        override fun onError(error: Throwable) {
                            // ignore toast message
                            errorHandler.proceed(error) { }
                        }
                    })
        }
        draftChangesObserver?.onNext(draftFields)
    }

    fun savePageDraftIfNeeded(draftFields: DraftFields, force: Boolean = false) {
        savePageDraftIfNeededCompletable(draftFields, force)
                .doOnComplete { viewState.onPageSaved() }
                .doOnSubscribe { viewState.showProgress(true) }
                .doAfterTerminate { viewState.showProgress(false) }
                .compositeSubscribe()
    }

    private fun savePageDraftIfNeededCompletable(draftFields: DraftFields, force: Boolean = false): Completable {
        if (isDraftNeeded && page != null) {
            val page = requireNotNull(page)
            return convertFormatsObservable(draftFields.formats)
                    .flatMapCompletable { nodes ->
                        if (force || draftFields.title != page.title || nodes != page.nodes.content) {
                            pageInteractor.savePageDraft(page.id, draftFields.title, draftFields.authorName, draftFields.authorUrl, nodes, getPageImageUrl(draftFields.formats))
                                    .doOnSuccess { page -> this.page = page }
                                    .ignoreElement()
                        } else {
                            Completable.complete()
                        }
                    }
        } else {
            return Completable.complete()
        }
    }

    fun discardDraftPageIfNeeded(title: String, formats: List<Format>) {
        val page = page
        if (page != null && title.isEmpty() && formats.isEmpty()) {
            pageInteractor.discardDraftPage(page)
                    .justSubscribe()
        }
    }

    fun convertHtml(html: String): List<Format> {
        val nodes = telegraphContentConverter.htmlToNodes(html)
        return convertNodes(nodes)
    }

    private fun hasNotUploadedImages(formats: List<Format>): Boolean {
        return formats.any { format -> format is ImageFormat && format.uploadStatus == ImageUploadStatus.InProgress }
    }

    private fun getPageImageUrl(formats: List<Format>): String? {
        var pageImageUrl: String? = null
        if (formats.isNotEmpty()) {
            val format = formats[0]
            if (format is ImageFormat) {
                pageImageUrl = format.url
            }
        }
        return pageImageUrl
    }

    private fun convertFormatsObservable(formats: List<Format>): Single<List<NodeElementData>> {
        return Single.fromCallable { convertFormats(formats) }
                .subscribeOn(Schedulers.newThread())
    }

    private fun convertFormats(formats: List<Format>): List<NodeElementData> {
        val resultNodes = arrayListOf<NodeElementData>()

        formats.flatMapTo(resultNodes) { telegraphContentConverter.htmlToNodes(it.toHtml()) }

        // normalize wrong text blocks
        val iterator = resultNodes.iterator()
        val paragraphTag = FormatType.PARAGRAPH.tag
        var i = 0
        while (iterator.hasNext()) {
            val node: NodeElementData = iterator.next()
            try {
                val formatType = FormatType.getByTag(node.tag)
                if (formatType?.isInline() == true || formatType == null) {
                    if (i != 0 && resultNodes[i - 1].tag == paragraphTag) {
                        resultNodes[i - 1].let {
                            if (it.children == null) it.children = arrayListOf()
                            it.children?.add(node)
                        }
                        iterator.remove()
                        i--
                    } else if (i < resultNodes.size - 1 && resultNodes[i + 1].tag == paragraphTag) {
                        resultNodes[i + 1].let {
                            if (it.children == null) it.children = arrayListOf()
                            it.children?.add(node)
                        }
                        iterator.remove()
                        i--
                    } else {
                        node.children = arrayListOf(node.copy())
                        node.tag = paragraphTag
                        node.text = null
                    }
                }
            } catch (error: Exception) {
                Timber.e(error)
            }

            i++
        }

        return resultNodes
    }

    private fun convertNodes(nodes: List<NodeElementData>): List<Format> {
        val items = arrayListOf<Format>()
        nodes.forEach { node ->
            val formatType =
                    if (node.tag == null && node.text != null) {
                        FormatType.PARAGRAPH
                    } else {
                        FormatType.getByTag(node.tag)
                    }

            try {
                when (formatType) {
                    FormatType.FIGURE -> items.add(convertFigure(node))
                    FormatType.IMAGE -> items.add(convertImage(node, ""))
                    FormatType.VIDEO -> items.add(convertVideo(node, ""))
                    FormatType.IFRAME -> items.add(convertIframe(node, ""))
                    else -> {
                        if (formatType != null) {
                            val html = telegraphContentConverter.nodesToHtml(arrayListOf(node))
                            items.add(Format(formatType, html = html))
                        } else {
                            throw IllegalArgumentException("formatType is missing for tag=${node.tag}")
                        }
                    }
                }
            } catch (error: java.lang.Exception) {
                Timber.e(error)
            }
        }
        return items
    }

    private fun convertFigure(node: NodeElementData): Format {
        val children = node.children ?: throw IllegalArgumentException("children can't be null for figure")

        var figureChildren: NodeElementData? = null
        var captionChildren: NodeElementData? = null

        when {
            children[0].tag == "figcaption" -> {
                if (children.size > 1) {
                    figureChildren = children[1]
                }
                captionChildren = children[0].children?.firstOrNull()
            }
            else -> {
                figureChildren = children[0]
                if (children.size > 1) {
                    captionChildren = children[1].children?.firstOrNull()
                }
            }
        }

        val caption = captionChildren?.text.orEmpty()

        if (figureChildren == null) {
            throw IllegalArgumentException("figure can't be null for node=$node")
        } else {
            return when (figureChildren.tag) {
                FormatType.IMAGE.tag -> convertImage(figureChildren, caption)
                FormatType.IFRAME.tag -> convertIframe(figureChildren, caption)
                FormatType.VIDEO.tag -> convertVideo(figureChildren, caption)
                else -> throw IllegalArgumentException("unsupported tag=${figureChildren.tag}")
            }
        }
    }

    private fun convertImage(node: NodeElementData, caption: String): ImageFormat {
        val attrs = node.attrs ?: throw IllegalArgumentException("attrs can't be null for image")
        val src = attrs["src"] ?: throw IllegalArgumentException("src can't be null for image")
        return ImageFormat(src, caption)
    }

    private fun convertIframe(node: NodeElementData, caption: String): MediaFormat {
        val attrs = node.attrs ?: throw IllegalArgumentException("attrs can't be null for iframe")
        val src = attrs["src"] ?: throw IllegalArgumentException("src can't be null for iframe")
        val html = telegraphContentConverter.nodesToHtml(arrayListOf(node))
        return MediaFormat(html, src, caption)
    }

    private fun convertVideo(node: NodeElementData, caption: String): VideoFormat {
        val attrs = node.attrs ?: throw IllegalArgumentException("attrs can't be null for video")
        val src = attrs["src"] ?: throw IllegalArgumentException("src can't be null for video")
        val html = telegraphContentConverter.nodesToHtml(arrayListOf(node))
        return VideoFormat(html, src, caption)
    }
}
