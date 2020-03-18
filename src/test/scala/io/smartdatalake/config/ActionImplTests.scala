/*
 * Smart Data Lake - Build your data lake the smart way.
 *
 * Copyright © 2019-2020 ELCA Informatique SA (<https://www.elca.ch>)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package io.smartdatalake.config

import com.typesafe.config.{Config, ConfigException, ConfigFactory}
import io.smartdatalake.config.SdlConfigObject._
import io.smartdatalake.workflow.action
import io.smartdatalake.workflow.action.customlogic.{CustomDfTransformerConfig, CustomFileTransformerConfig}
import org.scalatest.{FlatSpec, Matchers}


private[smartdatalake] class ActionImplTests extends FlatSpec with Matchers {

  val dataObjectConfig: Config = ConfigFactory.parseString(
    """
      |dataObjects = {
      | tdo1 = {
      |   type = io.smartdatalake.config.TestDataObject
      |   arg1 = foo
      |   args = [bar, "!"]
      | }
      | tdo2 = {
      |   type = io.smartdatalake.config.TestDataObject
      |   arg1 = goo
      |   args = [bar]
      | }
      | tdo3 = {
      |   type = CsvFileDataObject
      |   csv-options {
      |     header = true
      |   }
      |   path = foo1
      | }
      | tdo4 = {
      |   type = CsvFileDataObject
      |   csv-options {
      |     header = true
      |   }
      |   path = foo2
      | }
      |}
      |""".stripMargin).resolve

  "CopyAction" should "be parsable" in {

    val customTransformerConfig = CustomDfTransformerConfig(
      className = Some("io.smartdatalake.workflow.action.TestDfTransformer")
    )

    val config = ConfigFactory.parseString(
      """
        |actions = {
        | 123 = {
        |   type = CopyAction
        |   inputId = tdo1
        |   outputId = tdo2
        |   deleteInputFiles = false
        |   transformer = {
        |     class-name = io.smartdatalake.workflow.action.TestDfTransformer
        |   }
        | }
        |}
        |""".stripMargin).withFallback(dataObjectConfig).resolve

    implicit val registry: InstanceRegistry = ConfigParser.parse(config)
    registry.getActions.head shouldBe action.CopyAction(
      id = "123",
      inputId = "tdo1",
      outputId = "tdo2",
      transformer = Some(customTransformerConfig)
    )
  }

  "CustomFileAction" should "be parsable" in {

    val config = ConfigFactory.parseString(
      """
        |actions = {
        | 123 = {
        |   type = CustomFileAction
        |   inputId = tdo3
        |   outputId = tdo4
        |   transformer = {
        |     class-name = io.smartdatalake.config.TestFileTransformer
        |   }
        |   deleteDataAfterRead = true
        | }
        |}
        |""".stripMargin).withFallback(dataObjectConfig).resolve

    implicit val registry: InstanceRegistry = ConfigParser.parse(config)

    registry.getActions.head shouldBe action.CustomFileAction(
      id = "123",
      inputId = "tdo3",
      outputId = "tdo4",
      deleteDataAfterRead = true,
      transformer = CustomFileTransformerConfig(
        className = Some("io.smartdatalake.config.TestFileTransformer")
      )
    )
  }

  "Action" should "throw nice error when wrong DataObject type" in {

    val config = ConfigFactory.parseString(
      """
        |actions = {
        | 123 = {
        |   type = CustomFileAction
        |   inputId = tdo1
        |   outputId = tdo1
        |   transformer = {
        |     class-name = io.smartdatalake.config.TestFileTransformer
        |   }
        |   deleteInputFiles = true
        | }
        |}
        |""".stripMargin).withFallback(dataObjectConfig).resolve

    val thrown = the [ConfigException] thrownBy  ConfigParser.parse(config)

    thrown.getMessage should include ("123")
    thrown.getMessage should include ("tdo1")
  }
}
