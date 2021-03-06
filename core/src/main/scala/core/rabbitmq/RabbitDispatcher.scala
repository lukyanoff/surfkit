package io.surfkit.core.rabbitmq

import akka.actor.SupervisorStrategy._
import akka.util.ByteString
import io.surfkit.model._
import scala.concurrent.Future
import scala.concurrent.duration._
import scala.util.{Try, Success, Failure}

import akka.actor._
import io.surfkit.core.rabbitmq.RabbitDispatcher._

import play.api.libs.json.JsValue

import com.rabbitmq.client.{Connection, ConnectionFactory, ShutdownListener, ShutdownSignalException}
import com.rabbitmq.client.{Address => RabbitAddress}

import scala.collection.immutable.Queue
import scala.annotation.tailrec

object RabbitConfig {
  
  val userExchange = "surfkit.users"
  val sysExchange = "surfkit.sys"
  
}

object RabbitDispatcher {

  case object Connect
  case class ConnectModule(module:String, mapper: (io.surfkit.model.Api.Request) => Future[Api.Result])
  case object GetConnection

  case class RabbitMqAddress(host:String, port:Int)

  sealed trait State
  case object Connected extends State
  case object Disconnected extends State
  
  sealed trait Data
  case object NoBrokerConnection extends Data
  case class BrokerConnection(underlying: com.rabbitmq.client.Connection, publisher: ActorRef) extends Data
  
  def props(address: RabbitMqAddress) = Props(new RabbitDispatcher(address))
}

class RabbitDispatcher(address: RabbitMqAddress) extends Actor with FSM[State, Data] with ActorLogging {
  
  val reconnectIn = 10 seconds

  import akka.actor.OneForOneStrategy
  import akka.actor.SupervisorStrategy._
  import scala.concurrent.duration._

  override val supervisorStrategy =
    OneForOneStrategy(maxNrOfRetries = 10, withinTimeRange = 1 minute) {
      case _: ActorInitializationException => Stop
      case _: ActorKilledException         => Stop
      case t: Exception                    =>
        println(t)
        println("RESTARTING..")
        Restart
      case _ =>
        println("WTF!!!")
        Stop
    }


  val factory = {
    val cf = new ConnectionFactory()
    cf.setHost(address.host)
    cf.setPort(address.port)
    cf.setAutomaticRecoveryEnabled(true)
    cf
  }
  
  var msgBuffer: Queue[Api.ApiMessage] = Queue.empty
  
  val shutdownListener = new ShutdownListener {
    override def shutdownCompleted(cause: ShutdownSignalException) {
      log.warning("received ShutdownSignalException from RabbitMQ")
      log.warning(s"reason: ${cause.getReason}")
    }
  }
  
  startWith(Disconnected, NoBrokerConnection)
  
  when(Disconnected) {
    case Event(RabbitDispatcher.Connect, _) =>
      log.info("Connecting to RabbitMQ...")
      Try { factory.newConnection() } match {
        case Success(conn) =>
          conn.addShutdownListener(shutdownListener)
          val channel = conn.createChannel()
          val replyQueueName = channel.queueDeclare().getQueue()
          val publisher = context.actorOf(RabbitPublisher.props(channel, replyQueueName))
          context.actorOf(RabbitSysConsumer.props(channel, replyQueueName))
          goto(Connected) using BrokerConnection(conn, publisher)
        case Failure(f) =>
          log.error(f, s"Couldn't connect to RabbitMQ server at $address")
          log.info(s"Reconnecting in $reconnectIn")
          setTimer("RabbitMQ reconnection", RabbitDispatcher.Connect, reconnectIn, false)
          stay
      }
    case Event(RabbitDispatcher.ConnectModule(module,mapper), _) =>
      log.info(s"Connecting to RabbitMQ... for module $module")
      Try { factory.newConnection() } match {
        case Success(conn) =>
          conn.addShutdownListener(shutdownListener)
          val channel = conn.createChannel()
          val consumer = context.actorOf(RabbitModuleConsumer.props(module, channel, mapper))
          goto(Connected) using BrokerConnection(conn, consumer)
        case Failure(f) =>
          log.error(f, s"Couldn't connect to RabbitMQ server at $address")
          log.info(s"Reconnecting in $reconnectIn")
          setTimer("RabbitMQ reconnection", RabbitDispatcher.Connect, reconnectIn, false)
          stay
      }
    case Event(msg: Api.ApiMessage, _) =>
      msgBuffer = msgBuffer.enqueue(msg)
      stay
  }
  
  when(Connected) {
    case Event(Api.SendUser(uid, appId, req), BrokerConnection(_, publisher)) =>
      println("RabbitDispatcher Send USER.. about to publish message...")
      println(s"publisher: $publisher")
      println(s"publisher: $uid, $appId, $req")
      publisher ! RabbitPublisher.RabbitUserMessage(uid, appId, req)
      stay
    case Event(Api.SendSys(module, appId, corrId, msg), BrokerConnection(_, publisher)) =>
      publisher ! RabbitPublisher.RabbitSystemMessage(module, appId, corrId, msg)
      stay
    case Event(GetConnection, BrokerConnection(conn, _)) =>
      sender() ! conn
      stay
    case Event(msg @ RabbitSysConsumer.RabbitMessage(deliveryTag, correlationId, headers, body), BrokerConnection(conn, _)) =>
      context.parent.forward(msg)
      stay
  }
  
  onTransition {
    //this should happen only once, as the RabbitMQ client lib is handling connection autorecovery later on
    case Disconnected -> Connected => 
      log.info(s"Connected to RabbitMQ broker at $address")
      nextStateData match {
        case BrokerConnection(conn, publisher) =>
          //send all messages
          msgBuffer = send(msgBuffer, publisher)
        case _ => log.error("RabbitConnectionActor in state Connected but without a connection!!!")
      }
  }
  
  @tailrec
  private[this] def send(buffer: Queue[Api.ApiMessage], publisher: ActorRef): Queue[Api.ApiMessage] =
    buffer.dequeueOption match {
      case Some((Api.SendUser(uuid, provider, msg), buff)) =>
        publisher ! RabbitPublisher.RabbitUserMessage(uuid, provider, msg)
        send(buff, publisher)
      case Some((Api.SendSys(module, appId, corrId:String, msg), buff)) =>
        publisher ! RabbitPublisher.RabbitSystemMessage(module, appId, corrId, msg)
        send(buff, publisher)
      case None => buffer
    }
  
  override def preStart() = self ! RabbitDispatcher.Connect
  
  override def postStop() =
    stateData match {
      case BrokerConnection(conn, _) =>
        log.info(s"Closing connection to RabbitMQ on $address")
        conn.close()
      case _ => //there's no connection to close
    }
}