/**
 * Copyright 2011-2013 eBusiness Information, Groupe Excilys (www.ebusinessinformation.fr)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * 		http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.gatling.core.session

import scala.reflect.ClassTag

import com.typesafe.scalalogging.slf4j.Logging

import io.gatling.core.result.message.{ GroupStackEntry, KO, OK, Status }
import io.gatling.core.util.TimeHelper.nowMillis
import io.gatling.core.util.TypeHelper.TypeCaster
import io.gatling.core.validation.{ FailureWrapper, Validation }

/**
 * Private Gatling Session attributes
 */
object SessionPrivateAttributes {

	val privateAttributePrefix = "gatling."
}

case class SessionAttribute(session: Session, key: String) {

	def as[T]: T = session.attributes(key).asInstanceOf[T]
	def asOption[T]: Option[T] = session.attributes.get(key).map(_.asInstanceOf[T])
	def validate[T: ClassTag]: Validation[T] = session.attributes.get(key).map(_.asValidation[T]).getOrElse(undefinedSessionAttributeMessage(key).failure[T])
}

/**
 * Session class representing the session passing through a scenario for a given user
 *
 * This session stores all needed data between requests
 *
 * @constructor creates a new session
 * @param scenarioName the name of the current scenario
 * @param userId the id of the current user
 * @param data the map that stores all values needed
 */
case class Session(
	scenarioName: String,
	userId: Int,
	attributes: Map[String, Any] = Map.empty,
	startDate: Long = 0L,
	drift: Long = 0L,
	groupStack: List[GroupStackEntry] = Nil,
	statusStack: List[Status] = List(OK),
	interruptStack: List[PartialFunction[Session, Unit]] = Nil,
	counterStack: List[String] = Nil) extends Logging {

	val reducedInterruptStack = if (interruptStack.isEmpty) None else Some(interruptStack.reduceLeft(_ orElse _))
	def shouldInterrupt = reducedInterruptStack.map(_.isDefinedAt(this)).getOrElse(false)
	def interrupt = reducedInterruptStack.get(this)

	import SessionPrivateAttributes._

	private[gatling] def start = copy(startDate = nowMillis)

	def apply(name: String) = SessionAttribute(this, name)
	def setAll(newAttributes: (String, Any)*): Session = setAll(newAttributes.toIterable)
	def setAll(newAttributes: Iterable[(String, Any)]): Session = copy(attributes = attributes ++ newAttributes)
	def set(key: String, value: Any) = copy(attributes = attributes + (key -> value))
	def remove(key: String) = if (contains(key)) copy(attributes = attributes - key) else this
	def removeAll(keys: String*) = copy(attributes = attributes -- keys)
	def contains(attributeKey: String) = attributes.contains(attributeKey)

	private[gatling] def setDrift(drift: Long) = copy(drift = drift)
	private[gatling] def increaseDrift(time: Long) = copy(drift = time + drift)

	private[gatling] def enterGroup(groupName: String) = copy(groupStack = GroupStackEntry(groupName, nowMillis) :: groupStack, statusStack = OK :: statusStack)
	private[gatling] def exitGroup = statusStack match {
		case KO :: _ :: tail => copy(statusStack = KO :: tail, groupStack = groupStack.tail) // propagate failure to upper block
		case _ :: tail => copy(statusStack = tail, groupStack = groupStack.tail)
	}

	private[gatling] def enterTryMax(interrupt: PartialFunction[Session, Unit]): Session = enterInterruptable(interrupt).copy(statusStack = OK :: statusStack)
	private[gatling] def exitTryMax: Session = statusStack match {
		case KO :: _ :: tail => copy(statusStack = KO :: tail, interruptStack = interruptStack.tail) // propagate failure to upper block
		case _ :: tail => copy(statusStack = tail, interruptStack = interruptStack.tail)
	}
	def markAsFailed: Session = statusStack match {
		case OK :: tail => copy(statusStack = KO :: tail)
		case _ => this
	}

	def status: Status = if (statusStack.contains(KO)) KO else OK
	def resetStatus = statusStack match {
		case KO :: tail => copy(statusStack = OK :: tail)
		case _ => this
	}

	private[gatling] def enterInterruptable(interrupt: PartialFunction[Session, Unit]) = copy(interruptStack = interrupt :: interruptStack)
	private[gatling] def exitInterruptable = copy(interruptStack = interruptStack.tail)

	private def timestampName(counterName: String) = "timestamp." + counterName

	private[gatling] def incrementLoop(counterName: String) = counterStack match {
		case head :: _ if head == counterName =>
			val counterValue = attributes(counterName).asInstanceOf[Int] + 1
			copy(attributes = attributes + (counterName -> counterValue))
		case _ => copy(attributes = attributes + (counterName -> 0) + (timestampName(counterName) -> nowMillis), counterStack = counterName :: counterStack)
	}
	private[gatling] def exitLoop = counterStack match {
		case counterName :: tail => copy(attributes = attributes - counterName - timestampName(counterName), counterStack = tail)
		case _ => this
	}

	def loopCounterValue(counterName: String) = attributes(counterName).asInstanceOf[Int]
	def loopTimestampValue(counterName: String) = attributes(timestampName(counterName)).asInstanceOf[Long]
}
