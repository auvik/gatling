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
package io.gatling.core.result.terminator

import java.util.concurrent.CountDownLatch

import scala.concurrent.Future
import scala.util.{ Failure, Success }

import io.gatling.core.action.{ AkkaDefaults, BaseActor, system }
import io.gatling.core.config.GatlingConfiguration.configuration
import io.gatling.core.result.message.Flush

import akka.actor.{ ActorRef, Props }

object Terminator extends AkkaDefaults {

	private val terminator = system.actorOf(Props[Terminator])

	def askInit(latch: CountDownLatch, userCount: Int): Future[Any] = {
		terminator ? Initialize(latch, userCount)
	}

	def askDataWriterRegistration(dataWriter: ActorRef): Future[Any] = {
		terminator ? RegisterDataWriter(dataWriter)
	}

	def endUser {
		terminator ! EndUser
	}

	def forceTermination {
		terminator ! ForceTermination
	}
}

class Terminator extends BaseActor {

	import context._

	/**
	 * The countdown latch that will be decreased when all message are written and all scenarios ended
	 */
	private var latch: CountDownLatch = _
	private var endUserCount: Int = _

	private var registeredDataWriters: List[ActorRef] = Nil

	val uninitialized: Receive = {

		case Initialize(latch, userCount) =>
			logger.info("Initializing")
			this.latch = latch
			this.endUserCount = userCount * configuration.data.dataWriterClasses.size
			logger.info(s"Expecting $userCount EndUser messages to terminate")
			registeredDataWriters = Nil
			context.become(initialized)
			sender ! true
			logger.info("Initialized")
	}

	def flush {
		// just give DataWriters a chance to write more pending messages
		// not perfect, they still could receive messages after this flush
		logger.info("Asking DataWriters to flush")
		Future.sequence(registeredDataWriters.map(_ ? Flush))
			.onComplete {
				case Success(_) =>
					latch.countDown
					context.unbecome
				case Failure(e) => logger.error("Flushing registered DataWriters failed", e)
			}
	}

	val initialized: Receive = {

		case RegisterDataWriter(dataWriter: ActorRef) =>
			registeredDataWriters = dataWriter :: registeredDataWriters
			sender ! true
			logger.info("DataWriter registered")

		case EndUser =>
			endUserCount = endUserCount - 1
			if (endUserCount == 0) flush

		case ForceTermination => flush
	}

	def receive = uninitialized
}