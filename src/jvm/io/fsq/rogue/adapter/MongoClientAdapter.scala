// Copyright 2016 Foursquare Labs Inc. All Rights Reserved.

package io.fsq.rogue.adapter

import com.mongodb.{Block, ReadPreference, WriteConcern}
import com.mongodb.client.model.CountOptions
import io.fsq.rogue.{Query, QueryHelpers, RogueException}
import io.fsq.rogue.MongoHelpers.MongoBuilder
import org.bson.conversions.Bson


/** TODO(jacob): All of the collection methods implemented here should get rid of the
  *     option to send down a read preference, and just use the one on the query.
  */
abstract class MongoClientAdapter[MongoCollection[_], Document, MetaRecord, Record, Result[_]](
  collectionFactory: MongoCollectionFactory[MongoCollection, Document, MetaRecord, Record]
) {

  /** Wrap an empty result for a no-op query. */
  def wrapEmptyResult[T](value: T): Result[T]

  private def runCommand[M <: MetaRecord, T](
    descriptionFunc: () => String,
    query: Query[M, _, _]
  )(
    f: => T
  ): T = {
    // Use nanoTime instead of currentTimeMillis to time the query since
    // currentTimeMillis only has 10ms granularity on many systems.
    val start = System.nanoTime
    val instanceName: String = collectionFactory.getInstanceNameFromQuery(query)
    // Note that it's expensive to call descriptionFunc, it does toString on the Query
    // the logger methods are call by name
    try {
      QueryHelpers.logger.onExecuteQuery(query, instanceName, descriptionFunc(), f)
    } catch {
      case e: Exception => {
        val timeMs = (System.nanoTime - start) / 1000000
        throw new RogueException(
          s"Mongo query on $instanceName [${descriptionFunc()}] failed after $timeMs ms",
          e
        )
      }
    } finally {
      QueryHelpers.logger.log(query, instanceName, descriptionFunc(), (System.nanoTime - start) / 1000000)
    }
  }

  /* TODO(jacob): Can we move this to a better place? It needs access to the
   *    implementation of MongoCollection used, so currently our options are either
   *    MongoClientAdapter or MongoClientManager. Perhaps we want to abstract out some
   *    kind of utility helper?
   */
  protected def getCollectionName(collection: MongoCollection[Document]): String

  protected def countImpl(
    collection: MongoCollection[Document]
  )(
    filter: Bson,
    options: CountOptions
  ): Result[Long]

  protected def distinctImpl[T](
    resultAccessor: => T, // call by name
    accumulator: Block[Document]
  )(
    collection: MongoCollection[Document]
  )(
    fieldName: String,
    filter: Bson
  ): Result[T]

  protected def insertImpl[R <: Record](
    collection: MongoCollection[Document]
  )(
    record: R,
    document: Document
  ): Result[R]

  def count[
    M <: MetaRecord
  ](
    query: Query[M, _, _],
    readPreferenceOpt: Option[ReadPreference]
  ): Result[Long] = {
    val queryClause = QueryHelpers.transformer.transformQuery(query)
    QueryHelpers.validator.validateQuery(queryClause, collectionFactory.getIndexes(queryClause))
    val collection = collectionFactory.getMongoCollectionFromQuery(query, readPreferenceOpt)
    val descriptionFunc = () => MongoBuilder.buildConditionString("count", query.collectionName, queryClause)
    // TODO(jacob): This cast will always succeed, but it should be removed once there is a
    //    version of MongoBuilder that speaks the new CRUD api.
    val condition = MongoBuilder.buildCondition(queryClause.condition).asInstanceOf[Bson]
    val options = {
      new CountOptions()
        .limit(queryClause.lim.getOrElse(0))
        .skip(queryClause.sk.getOrElse(0))
    }

    runCommand(descriptionFunc, queryClause) {
      countImpl(collection)(condition, options)
    }
  }

  private def distinctRunner[M <: MetaRecord, T](
    resultAccessor: => T, // call by name
    accumulator: Block[Document]
  )(
    query: Query[M, _, _],
    fieldName: String,
    readPreferenceOpt: Option[ReadPreference]
  ): Result[T] = {
    val queryClause = QueryHelpers.transformer.transformQuery(query)
    QueryHelpers.validator.validateQuery(queryClause, collectionFactory.getIndexes(queryClause))
    val collection = collectionFactory.getMongoCollectionFromQuery(query, readPreferenceOpt)
    val descriptionFunc = () => MongoBuilder.buildConditionString("distinct", query.collectionName, queryClause)
    // TODO(jacob): This cast will always succeed, but it should be removed once there is a
    //    version of MongoBuilder that speaks the new CRUD api.
    val condition = MongoBuilder.buildCondition(queryClause.condition).asInstanceOf[Bson]

    runCommand(descriptionFunc, queryClause) {
      distinctImpl(resultAccessor, accumulator)(collection)(fieldName, condition)
    }
  }

  def countDistinct[M <: MetaRecord, FieldType](
    query: Query[M, _, _],
    fieldName: String,
    readPreferenceOpt: Option[ReadPreference]
  ): Result[Long] = {
    var count = 0L
    val counter = new Block[Document] {
      override def apply(value: Document): Unit = {
        count += 1
      }
    }

    distinctRunner(count, counter)(query, fieldName, readPreferenceOpt)
  }

  // TODO(jacob): Investigate how hard it would be to remove the cast here and instead
  //    pass down an instance of Class[FieldType] to hand to the driver.
  def distinct[M <: MetaRecord, FieldType](
    query: Query[M, _, _],
    fieldName: String,
    readPreferenceOpt: Option[ReadPreference]
  ): Result[Seq[FieldType]] = {
    val fieldsBuilder = Vector.newBuilder[FieldType]
    val appender = new Block[Document] {
      override def apply(value: Document): Unit = {
        fieldsBuilder += value.asInstanceOf[FieldType]
      }
    }

    distinctRunner(fieldsBuilder.result(): Seq[FieldType], appender)(query, fieldName, readPreferenceOpt)
  }

  def insert[R <: Record](
    record: R,
    document: Document,
    writeConcernOpt: Option[WriteConcern]
  ): Result[R] = {
    val collection = collectionFactory.getMongoCollectionFromRecord(record, writeConcernOpt = writeConcernOpt)
    val collectionName = getCollectionName(collection)
    val instanceName = collectionFactory.getInstanceNameFromRecord(record)
    QueryHelpers.logger.onExecuteWriteCommand(
      "insert",
      collectionName,
      instanceName,
      collectionFactory.documentToString(document),
      insertImpl(collection)(record, document)
    )
  }
}
