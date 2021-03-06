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

package it.pagopa.dbtographql

import java.time.Duration
import java.time.temporal.TemporalAmount

import com.nimbusds.jwt.EncryptedJWT
import it.pagopa.dbtographql.database.{DatabaseDataMgmt, DatabaseMetadataMgmt}
import it.pagopa.dbtographql.schema.{SchemaDefinition, SchemaLoginDefinition}
import it.pagopa.dbtographql.sessionmanagement.SessionManagement._
import it.pagopa.dbtographql.sessionmanagement.{SessionManagement, WithLogin}
import org.scalatest.matchers.must.Matchers
import org.scalatest.wordspec.AnyWordSpec

@SuppressWarnings(
  Array(
    "org.wartremover.warts.Any",
    "org.wartremover.warts.AsInstanceOf"
  )
)
class ConnectionManagementSpec extends AnyWordSpec with Matchers with SessionManagement with WithLogin with DatabaseMetadataMgmt with DatabaseDataMgmt with SchemaDefinition with SchemaLoginDefinition {

  locally {
    val _ = Class.forName("org.h2.Driver")
  }

  val DATABASE_URL: String = "jdbc:h2:mem:db2"

  override val getConnectionUri: String = DATABASE_URL

  override def getPrivateKeyPath = "etc/key.priv"

  override def getPublicKeyPath = "etc/key.pub"

  override def getTokenLifetime: TemporalAmount = Duration.ofMinutes(30)

  "Generating the token" must {
    "work successfully" in {
      val token = generateToken("username","password","db")
      val jwt = EncryptedJWT.parse(token)
      jwt.decrypt(decrypter)
      val _ = jwt.getJWTClaimsSet.getClaim("username").asInstanceOf[String] must be("username")
      val _ = jwt.getJWTClaimsSet.getClaim("password").asInstanceOf[String] must be("password")
      val _ = jwt.getJWTClaimsSet.getClaim("database").asInstanceOf[String] must be("db")
    }
  }

  "Connection management" must {
    "work successfully" in {
      val token = login("", "", "TEST")
      val connection = connections(token)
      val stm = connection.createStatement
      val sql =
        """
          |create schema `test`;
          |create table test.test_datatypes(
          |   BOOLEAN_TYPE     BOOLEAN,
          |   TIMESTAMP_TYPE   TIMESTAMP,
          |   DATE_TYPE        DATE,
          |   LONGVARCHAR_TYPE LONGVARCHAR,
          |   VARCHAR_TYPE     VARCHAR,
          |   CHAR_TYPE        CHAR,
          |   DECIMAL_TYPE     DECIMAL,
          |   NUMERIC_TYPE     NUMERIC,
          |   DOUBLE_TYPE      DOUBLE,
          |   REAL_TYPE        REAL,
          |   FLOAT_TYPE       FLOAT,
          |   BIGINT_TYPE      BIGINT,
          |   INTEGER_TYPE     INTEGER,
          |   SMALLINT_TYPE    SMALLINT,
          |   TINYINT_TYPE     TINYINT
          |   );
          |""".stripMargin
      val _ = stm.execute(sql)
      val _ = connections.size must be(1)
      val _ = getSchema(token).foreach(_.renderPretty.length must be > 20)
      val _ = logout(token)
      val _ = connections.size must be(0)
      val _ = connection.close()
    }
  }
}
