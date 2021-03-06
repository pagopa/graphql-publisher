/*
 * Copyright 2020 Pagopa S.p.A.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package it.pagopa.dbtographql.database

import cats.data.Validated.{Invalid, Valid}
import cats.data.ValidatedNel
import cats.implicits.{catsSyntaxEq, _}
import it.pagopa.dbtographql.database.DatabaseMetadataModel.{
  ColumnMetadata,
  DatabaseMetadata,
  TableMetadata
}
import org.slf4j.LoggerFactory

import java.sql.{Connection, ResultSet, Types}
import scala.annotation.{switch, tailrec}

@SuppressWarnings(
  Array(
    "org.wartremover.warts.Null",
    "org.wartremover.warts.Nothing",
    "org.wartremover.warts.TraversableOps"
  )
)
trait DatabaseMetadataMgmt {

  private val logger = LoggerFactory.getLogger(classOf[DatabaseMetadataMgmt])

  @inline protected def isNumericField(columnType: Int): Boolean = {
    (columnType: @switch) match {
      case Types.DOUBLE | Types.FLOAT | Types.SMALLINT | Types.INTEGER |
          Types.BIGINT | Types.DECIMAL =>
        true
      case _ => false
    }
  }

  protected def resultSetToList(
      resultSet: ResultSet): List[Seq[(String, AnyRef, String)]] = {
    val metaData = resultSet.getMetaData
    val columnCount = resultSet.getMetaData.getColumnCount

    @inline def getRow: Seq[(String, AnyRef, String)] = (1 to columnCount).map(
      i =>
        (
          metaData.getColumnName(i),
          resultSet.getObject(i),
          metaData.getColumnTypeName(i)
      )
    )

    @tailrec
    def resultSetToList(resultSet: ResultSet,
                        list: List[Seq[(String, AnyRef, String)]])
      : List[Seq[(String, AnyRef, String)]] = {
      if (resultSet.next())
        resultSetToList(resultSet, getRow :: list)
      else
        list
    }

    resultSetToList(resultSet, List.empty[Seq[(String, AnyRef, String)]])
  }

  private def validateColumnName(
      columnName: String): ValidatedNel[String, String] =
    if (columnName.matches("^[_a-zA-Z][_a-zA-Z0-9]*$"))
      columnName.validNel
    else
      s"""The column $columnName doesn't satisfy the following regex: ^[_a-zA-Z][_a-zA-Z0-9]*$$\n""".invalidNel

  private def validateTableMetadata(tableMetadata: TableMetadata) = {
    if (tableMetadata.columns.isEmpty) {
      logger.error(
        s"${tableMetadata.databaseName}.${tableMetadata.tableName} has an empty column set")
      false
    } else {
      val validationResult: ValidatedNel[String, String] =
        tableMetadata.columns.tail
          .foldLeft(validateColumnName(tableMetadata.columns.head.columnName))(
            (accum, item) => validateColumnName(item.columnName).combine(accum))
      validationResult match {
        case Valid(_) =>
          true
        case Invalid(e) =>
          logger.error(
            s"${tableMetadata.databaseName}.${tableMetadata.tableName}'s column names have the following validation errors: \n ${e.toList
              .mkString("\n")}")
          false
      }
    }
  }

  protected def getDatabaseMetadata(
      connection: Connection,
      database: String): DatabaseMetadataModel.DatabaseMetadata = {
    logger.info(s"About to get metadata for the database $database")
    val metaData = connection.getMetaData
    val resultSet = metaData.getTables(null, null, null, Array("TABLE"))
    val tables = resultSetToList(resultSet)
      .filter(r => r(1)._2.asInstanceOf[String] === database)
      .map(r => r(2)._2.asInstanceOf[String])
    DatabaseMetadata(
      database,
      tables.map(table => {
        val columns =
          resultSetToList(metaData.getColumns(null, null, table, null))
            .map(
              r =>
                ColumnMetadata(r(3)._2.asInstanceOf[String],
                               r(5)._2.asInstanceOf[String],
                               r(4)._2.asInstanceOf[Int]))
            .distinct
        TableMetadata(table, database, columns)
      }) filter validateTableMetadata
    )
  }

}
