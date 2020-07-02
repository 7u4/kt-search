package io.inbot.eskotlinwrapper

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.delay
import mu.KotlinLogging
import org.apache.commons.lang3.RandomUtils
import org.elasticsearch.ElasticsearchStatusException
import org.elasticsearch.action.DocWriteRequest
import org.elasticsearch.action.admin.indices.alias.get.GetAliasesRequest
import org.elasticsearch.action.admin.indices.delete.DeleteIndexRequest
import org.elasticsearch.action.admin.indices.refresh.RefreshRequest
import org.elasticsearch.action.bulk.BulkItemResponse
import org.elasticsearch.action.delete.DeleteRequest
import org.elasticsearch.action.get.GetRequest
import org.elasticsearch.action.get.GetResponse
import org.elasticsearch.action.index.IndexRequest
import org.elasticsearch.action.search.SearchRequest
import org.elasticsearch.action.support.ActiveShardCount
import org.elasticsearch.action.support.WriteRequest
import org.elasticsearch.client.RequestOptions
import org.elasticsearch.client.RestHighLevelClient
import org.elasticsearch.client.core.CountRequest
import org.elasticsearch.client.countAsync
import org.elasticsearch.client.createAsync
import org.elasticsearch.client.deleteAsync
import org.elasticsearch.client.getAliasAsync
import org.elasticsearch.client.getAsync
import org.elasticsearch.client.indexAsync
import org.elasticsearch.client.indices.CreateIndexRequest
import org.elasticsearch.client.refreshAsync
import org.elasticsearch.client.searchAsync
import org.elasticsearch.cluster.metadata.AliasMetadata
import org.elasticsearch.common.unit.TimeValue
import org.elasticsearch.common.xcontent.XContentType
import org.elasticsearch.rest.RestStatus
import org.elasticsearch.search.fetch.subphase.FetchSourceContext

private val logger = KotlinLogging.logger {}

/**
 * Repository abstraction that allows you to work with indices asynchronously via co-routines.
 *
 * You should create a Repository for each index you work with. You need to specify a [ModelReaderAndWriter] for serialization and deserialization.
 *
 * @see `RestHighLevelClient.asyncIndexRepository` for a convenient way to create a repository.
 *
 * @param T the type of the object that is stored in the index.
 * @param indexName name of the index
 * @param indexReadAlias Alias used for read operations. If you are using aliases, you can separate reads and writes. Defaults to indexName.
 * @param indexWriteAlias Alias used for write operations. If you are using aliases, you can separate reads and writes. Defaults to indexName.
 * @param type the type of the documents in the index; defaults to null. Since ES 6, there can only be one type. Types are deprecated in ES 7 and removed in ES 8.
 * @param modelReaderAndWriter serialization of your model class.
 * @param refreshAllowed if false, the refresh will throw an exception. Defaults to false.
 * @param defaultRequestOptions passed on all API calls. Defaults to `RequestOptions.DEFAULT`. Use this to set custom headers or override on each call on the repository.
 * @param fetchSourceContext if not null, will be passed to Get and Search requests.
 *
 */
@Suppress("unused")
class AsyncIndexRepository<T : Any>(
    val indexName: String,
    val client: RestHighLevelClient,
    internal val modelReaderAndWriter: ModelReaderAndWriter<T>,
    private val refreshAllowed: Boolean = false,
    @Deprecated("Types are deprecated in ES 7.x and will be removed in v8") val type: String? = null,
    val indexWriteAlias: String = indexName,
    val indexReadAlias: String = indexWriteAlias,
    private val fetchSourceContext: FetchSourceContext? = null,
    internal val defaultRequestOptions: RequestOptions = RequestOptions.DEFAULT
) {
    /**
     * Create the index.
     *
     * @param block customize the `CreateIndexRequest`
     *
     * ```
     * {
     *   source(settings, XContentType.JSON)
     * }
     * ```
     */
    suspend fun createIndex(
        requestOptions: RequestOptions = this.defaultRequestOptions,
        waitForActiveShards: ActiveShardCount? = null,
        block: suspend CreateIndexRequest.() -> Unit
    ) {
        val indexRequest = CreateIndexRequest(indexName)
        if (waitForActiveShards != null) {
            indexRequest.waitForActiveShards(waitForActiveShards)
        }
        block.invoke(indexRequest)

        client.indices().createAsync(indexRequest, requestOptions)
    }

    /**
     * Delete the index associated with the repository. Returns true if successful or false if the index did not exist
     */
    suspend fun deleteIndex(requestOptions: RequestOptions = this.defaultRequestOptions): Boolean {
        return try {
            client.indices().deleteAsync(DeleteIndexRequest(indexName), requestOptions)
            true
        } catch (e: ElasticsearchStatusException) {
            if (e.status().status == 404) {
                // 404 means there was nothing to delete
                false
            } else {
                // this would be unexpected
                throw e
            }
        }
    }

    /**
     * Returns a set of the current `AliasMetaData` associated with the `indexName`.
     */
    suspend fun currentAliases(requestOptions: RequestOptions = this.defaultRequestOptions): Set<AliasMetadata> {
        return client.indices().getAliasAsync(GetAliasesRequest().indices(indexName), requestOptions).aliases[this.indexName]
            ?: throw IllegalStateException("Inde $indexName does not exist")
    }

    /**
     * Index a document with a given `id`. Set `create` to `false` for upserts. Otherwise it fails on creating documents that already exist.
     *
     * You can optionally specify `seqNo` and `primaryTerm` to implement optimistic locking. However, you should use
     * [update] which does this for you.
     */
    @Suppress("DEPRECATION")
    suspend fun index(
        id: String,
        obj: T,
        create: Boolean = true,
        seqNo: Long? = null,
        primaryTerm: Long? = null,
        requestOptions: RequestOptions = this.defaultRequestOptions
    ) {
        val indexRequest = IndexRequest()
            .index(indexWriteAlias)
            .id(id)
            .create(create)
            .source(modelReaderAndWriter.serialize(obj), XContentType.JSON)
        if (!type.isNullOrBlank()) {
            indexRequest.type(type)
        }
        if (seqNo != null) {
            indexRequest.setIfSeqNo(seqNo)
            indexRequest.setIfPrimaryTerm(primaryTerm ?: throw IllegalArgumentException("you must also set primaryTerm when setting a seqNo"))
        }
        client.indexAsync(
            indexRequest, requestOptions
        )
    }

    /**
     * Updates document identified by `id` by fetching the current version with [get] and then applying the [transformFunction] to produce the updated version.
     *
     * if [maxUpdateTries] > 0, it will deal with version conflicts (e.g. due to concurrent updates) by retrying with the latest version.
     */
    suspend fun update(
        id: String,
        maxUpdateTries: Int = 2,
        requestOptions: RequestOptions = this.defaultRequestOptions,
        transformFunction: suspend (T) -> T
    ) {
        update(0, id, transformFunction, maxUpdateTries, requestOptions)
    }

    @Suppress("DEPRECATION")
    private suspend fun update(
        tries: Int,
        id: String,
        transformFunction: suspend (T) -> T,
        maxUpdateTries: Int,
        requestOptions: RequestOptions

    ) {
        try {
            val getRequest = GetRequest().index(indexWriteAlias).id(id)
            if (!type.isNullOrBlank()) {
                getRequest.type(type)
            }

            val response =
                client.getAsync(getRequest, requestOptions)

            val sourceAsBytes = response.sourceAsBytes
            if (sourceAsBytes != null) {
                val currentValue = modelReaderAndWriter.deserialize(sourceAsBytes)
                val transformed = transformFunction.invoke(currentValue)
                index(id, transformed, create = false, seqNo = response.seqNo, primaryTerm = response.primaryTerm)
                if (tries > 0) {
                    // if you start seeing this a lot, you have a lot of concurrent updates to the same thing; not good
                    logger.warn { "retry update $id succeeded after tries=$tries" }
                }
            } else {
                throw IllegalStateException("id $id not found")
            }
        } catch (e: ElasticsearchStatusException) {

            if (e.status().status == 409) {
                if (tries < maxUpdateTries) {
                    // we got a version conflict, retry after sleeping a bit (without this failures are more likely
                    delay(RandomUtils.nextLong(50, 500))
                    update(tries + 1, id, transformFunction, maxUpdateTries, requestOptions)
                } else {
                    throw IllegalStateException("update of $id failed after $tries attempts")
                }
            } else {
                // something else is wrong
                throw e
            }
        }
    }

    /**
     * Deletes the object object identified by `id`.
     */
    @Suppress("DEPRECATION")
    suspend fun delete(id: String, requestOptions: RequestOptions = this.defaultRequestOptions) {
        val deleteRequest = DeleteRequest().index(indexWriteAlias).id(id)
        if (!type.isNullOrBlank()) {
            deleteRequest.type(type)
        }

        client.deleteAsync(deleteRequest, requestOptions)
    }

    /**
     * Returns the deserialized [T] for the document identified by `id`.
     */

    suspend fun get(id: String): T? {
        return getWithGetResponse(id)?.first
    }

    /**
     * Returns a `Pair` of the deserialized [T] and the `GetResponse` with all the relevant metadata.
     */
    @Suppress("DEPRECATION")
    suspend fun getWithGetResponse(
        id: String,
        requestOptions: RequestOptions = this.defaultRequestOptions
    ): Pair<T, GetResponse>? {
        val getRequest = GetRequest().index(indexReadAlias).id(id)
        if (fetchSourceContext != null) {
            getRequest.fetchSourceContext(fetchSourceContext)
        }
        if (!type.isNullOrBlank()) {
            getRequest.type(type)
        }

        val response = client.getAsync(getRequest, requestOptions)
        val sourceAsBytes = response.sourceAsBytes

        if (sourceAsBytes != null) {
            val deserialized = modelReaderAndWriter.deserialize(sourceAsBytes)

            return Pair(deserialized, response)
        }
        return null
    }

    /**
     * Create a [BulkIndexingSession] and use it with the [operationsBlock]. Inside the block you can call operations
     * like `index` and other functions exposed by [BulkIndexingSession]. The resulting bulk
     * operations are automatically grouped in bulk requests of size [bulkSize] and sent off to Elasticsearch. For each
     * operation there will be a call to the [itemCallback]. This allows you to keep track of failures, do logging,
     * or implement retries. If you leave this `null`, the default callback implementation defined in
     * [BulkIndexingSession] is used.
     **
     * See [BulkIndexingSession] for the meaning of the other parameters.
     *
     */
    suspend fun bulk(
        bulkSize: Int = 100,
        retryConflictingUpdates: Int = 0,
        refreshPolicy: WriteRequest.RefreshPolicy = WriteRequest.RefreshPolicy.WAIT_UNTIL,
        itemCallback: suspend ((AsyncBulkOperation<T>, BulkItemResponse) -> Unit) = { operation, itemResponse ->
            if (itemResponse.isFailed) {
                if (retryConflictingUpdates > 0 && DocWriteRequest.OpType.UPDATE === itemResponse.opType && itemResponse.failure.status === RestStatus.CONFLICT) {
                    update(operation.id, retryConflictingUpdates, defaultRequestOptions, operation.updateFunction)
                    logger.debug { "retried updating ${operation.id} after version conflict" }
                } else {
                    logger.warn { "failed item ${itemResponse.itemId} ${itemResponse.opType} on ${itemResponse.id} because ${itemResponse.failure.status} ${itemResponse.failureMessage}" }
                }
            }
        },
        bulkDispatcher: CoroutineDispatcher? = null,
        operationsBlock: suspend AsyncBulkIndexingSession<T>.() -> Unit
    ) {
        AsyncBulkIndexingSession.asyncBulk(
            bulkSize = bulkSize,
            retryConflictingUpdates = retryConflictingUpdates,
            refreshPolicy = refreshPolicy,
            itemCallback = itemCallback,
            client = this.client,
            bulkDispatcher = bulkDispatcher,
            repository = this,
            block = operationsBlock
        )
    }

    /**
     * Call the refresh API on elasticsearch. You should not use this other than in tests. E.g. when testing search
     * queries, you often want to refresh after indexing before calling search
     *
     * Throws UnsupportedOperationException if you do not explicitly opt in to this by setting the `refreshAllowed` to
     * true when creating the repository.
     */
    suspend fun refresh() {
        if (refreshAllowed) {
            // calling this is not safe in production settings but highly useful in tests
            client.indices().refreshAsync(RefreshRequest(), defaultRequestOptions)
        } else {
            throw UnsupportedOperationException("refresh is not allowed; you need to opt in by setting refreshAllowed to true")
        }
    }

    /**
     * Perform an asynchronous search against your index. Works similar to the synchronous version, except it
     * returns `AsyncSearchResults` with a flow of responses that get mapped to `T`.
     *
     * Similar to the synchronous version, it supports scrolling.
     */
    suspend fun search(
        scrolling: Boolean = false,
        scrollTtlInMinutes: Long = 1,
        requestOptions: RequestOptions = this.defaultRequestOptions,
        block: SearchRequest.() -> Unit
    ): AsyncSearchResults<T> {

        val searchResponse = client.searchAsync(requestOptions) {
            indices(indexReadAlias)
            if (scrolling) {
                scroll(TimeValue.timeValueMinutes(scrollTtlInMinutes))
            }

            block.invoke(this)

            if (fetchSourceContext != null && source()?.fetchSource() == null) {
                source()?.fetchSource(fetchSourceContext)
            }
        }

        return AsyncSearchResults(client, modelReaderAndWriter, scrollTtlInMinutes, searchResponse, defaultRequestOptions)
    }

    suspend fun count(requestOptions: RequestOptions = this.defaultRequestOptions, block: CountRequest.() -> Unit = {}): Long {
        val request = CountRequest(indexReadAlias)
        block.invoke(request)
        val resp = client.countAsync(request, requestOptions)
        return resp.count
    }
}
