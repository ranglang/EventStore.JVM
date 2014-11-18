package eventstore
package tcp

import akka.actor.Status.Failure
import akka.actor.{ Terminated, ActorRef }
import akka.io.Tcp._
import akka.io.{ Tcp, IO }
import akka.testkit.{ TestProbe, TestActorRef }
import akka.util.ByteIterator
import java.net.InetSocketAddress
import java.nio.ByteOrder
import org.specs2.mock.Mockito
import scala.concurrent.duration._
import scala.util.{ Try, Success }

class ConnectionActorSpec extends util.ActorSpec with Mockito {

  val off = 1.minute

  "Connection Actor" should {

    "receive init.Event while connecting" in new TestScope {
      val id = randomUuid
      client ! TcpPackageOut(Authenticate, id)
      client ! init.Event(TcpPackageIn(Success(Authenticated), id))
      expectMsg(Authenticated)
    }

    "receive init.Event while connected" in new TestScope {
      sendConnected()

      client ! Authenticate
      val correlationId = pipeline.expectMsgPF() {
        case init.Command(TcpPackageOut(Authenticate, x, `credentials`)) => x
      }

      client ! init.Event(TcpPackageIn(Success(Authenticated)))
      expectNoMsg(duration)

      client ! init.Event(TcpPackageIn(Success(Authenticated), correlationId))
      expectMsg(Authenticated)
    }

    "receive init.Event while reconnecting" in new TestScope {
      sendConnected()
      client ! Authenticate
      val correlationId = pipeline.expectMsgPF() {
        case init.Command(TcpPackageOut(Authenticate, x, `credentials`)) => x
      }
      client ! PeerClosed
      expectConnect()

      client ! init.Event(TcpPackageIn(Success(Authenticated)))
      expectNoMsg(duration)

      client ! init.Event(TcpPackageIn(Success(Authenticated), correlationId))
      expectMsg(Authenticated)
    }

    "not reconnect if never connected before" in new TestScope {
      client ! CommandFailed(connect)
      expectNoMsgs()
      expectTerminated()
    }

    "not reconnect when connection lost if maxReconnections == 0" in new TcpScope {
      val (_, tcpConnection) = connect(settings.copy(maxReconnections = 0))
      tcpConnection ! Abort
      expectMsg(Aborted)
      expectNoMsg()
    }

    "reconnect when connection lost" in new TcpScope {
      val (_, tcpConnection) = connect(settings.copy(maxReconnections = 1, reconnectionDelayMin = 100.millis))
      tcpConnection ! Abort
      expectMsg(Aborted)
      expectMsgType[Connected]
    }

    "reconnect when connection actor died" in new TestScope {
      sendConnected()
      system stop connection.ref
      verifyReconnections(settings.maxReconnections)
      expectNoMsgs()

      override def settings = Settings(maxReconnections = 1, reconnectionDelayMin = 100.millis)
    }

    "reconnect when pipeline actor died" in new TestScope {
      sendConnected()
      system stop pipeline.ref
      verifyReconnections(settings.maxReconnections)
      expectNoMsgs()

      override def settings = Settings(maxReconnections = 1, reconnectionDelayMin = 100.millis)
    }

    "keep trying to reconnect for maxReconnections times" in new TestScope {
      sendConnected()
      client ! PeerClosed
      verifyReconnections(settings.maxReconnections)
      expectNoMsgs()

      override def settings = Settings(maxReconnections = 5, reconnectionDelayMin = 100.millis)
    }

    "use reconnectionDelay from settings" in new TestScope {
      sendConnected()
      client ! PeerClosed
      tcp.expectNoMsg(300.millis)
      verifyReconnections(settings.maxReconnections)

      override def settings = Settings(maxReconnections = 3, reconnectionDelayMin = 500.millis)
    }

    "reconnect if heartbeat timed out" in new TcpScope {
      val (_, tcpConnection) = connect(settings.copy(heartbeatTimeout = 600.millis))
      val req = expectPack
      req.message mustEqual Success(HeartbeatRequest)
      expectMsg(PeerClosed)
      expectMsgType[Connected]
    }

    "not reconnect if heartbeat response received in time" in new TcpScope {
      val (_, tcpConnection) = connect()

      val req = expectPack
      req.message mustEqual Success(HeartbeatRequest)

      tcpConnection ! write(TcpPackageOut(HeartbeatResponse, req.correlationId))
      expectPack.message mustEqual Success(HeartbeatRequest)
    }

    "close connection if heartbeat timed out and maxReconnections == 0" in new TcpScope {
      val (_, tcpConnection) = connect(settings.copy(maxReconnections = 0))
      expectPack.message mustEqual Success(HeartbeatRequest)
      expectMsg(PeerClosed)
      expectNoMsg()
    }

    "not close connection if heartbeat response received in time" in new TcpScope {
      val (_, tcpConnection) = connect(settings.copy(maxReconnections = 0))

      val req = expectPack
      req.message mustEqual Success(HeartbeatRequest)

      tcpConnection ! write(TcpPackageOut(HeartbeatResponse, req.correlationId))

      expectPack.message mustEqual Success(HeartbeatRequest)
    }

    "respond with HeartbeatResponseCommand on HeartbeatRequestCommand" in new TcpScope {
      val (_, tcpConnection) = connect(settings.copy(maxReconnections = 0))
      val req = TcpPackageOut(HeartbeatRequest)
      tcpConnection ! write(req)

      val res = expectPack
      res.correlationId mustEqual req.correlationId
      res.message mustEqual Success(HeartbeatResponse)
    }

    "ping" in new TcpScope {
      val (connection, tcpConnection) = connect()
      connection ! Ping

      val req = expectPack
      req.message mustEqual Success(Ping)

      tcpConnection ! write(TcpPackageOut(Pong, req.correlationId))
    }

    "pong" in new TcpScope {
      val (_, tcpConnection) = connect()

      tcpConnection ! write(Ping)
      expectPack.message mustEqual Success(Pong)
    }

    "stash Out message while connecting for the first time" in new TestScope {
      client ! Ping
      sendConnected()
      pipeline.expectMsgPF() { case init.Command(TcpPackageOut(Ping, _, _)) => }
    }

    "stash TcpPackageOut message while connecting for the first time" in new TestScope {
      val pack = TcpPackageOut(Ping)
      client ! pack
      sendConnected()
      pipeline.expectMsg(init.Command(pack))
    }

    "reply with OperationTimedOut if no reply received" in new OperationTimedOutScope {
      sendConnected()

      client ! TcpPackageOut(Ping, id, credentials)
      client ! init.Event(TcpPackageIn(Try(Pong)))

      client ! Authenticate
      client ! init.Event(TcpPackageIn(Try(Authenticated)))

      expectNoMsg(100.millis)
      expectOperationTimedOut(Ping, Authenticate)
      client ! init.Event(TcpPackageIn(Try(Pong), id))
      expectNoMsg(100.millis)
    }

    "reply with OperationTimedOut if not connected within timeout" in new OperationTimedOutScope {
      client ! TcpPackageOut(Ping, id, credentials)
      client ! init.Event(TcpPackageIn(Try(Pong)))

      client ! Authenticate
      client ! init.Event(TcpPackageIn(Try(Authenticated)))

      expectNoMsg(100.millis)
      expectOperationTimedOut(Ping, Authenticate)
      client ! init.Event(TcpPackageIn(Try(Pong), id))
      expectNoMsg(100.millis)
    }

    "reply with OperationTimedOut if not reconnected within timeout" in new OperationTimedOutScope {
      client ! TcpPackageOut(Ping, id, credentials)
      client ! init.Event(TcpPackageIn(Try(Pong)))

      client ! Authenticate
      client ! init.Event(TcpPackageIn(Try(Authenticated)))

      client ! PeerClosed

      expectNoMsg(100.millis)
      expectOperationTimedOut(Ping, Authenticate)
      client ! init.Event(TcpPackageIn(Try(Pong), id))
      expectNoMsg(100.millis)
    }

    "reply with OperationTimedOut if not reconnected within timeout" in new OperationTimedOutScope {
      sendConnected()
      client ! PeerClosed

      client ! TcpPackageOut(Ping, id, credentials)
      client ! init.Event(TcpPackageIn(Try(Pong)))

      client ! Authenticate
      client ! init.Event(TcpPackageIn(Try(Authenticated)))

      client ! PeerClosed

      expectNoMsg(100.millis)
      expectOperationTimedOut(Ping, Authenticate)
      client ! init.Event(TcpPackageIn(Try(Pong), id))
      expectNoMsg(100.millis)
    }

    "reply with OperationTimedOut if no reply received" in new OperationTimedOutScope {
      sendConnected()
      client ! TcpPackageOut(Ping, id, credentials)
      client ! init.Event(TcpPackageIn(Try(Pong)))

      client ! Authenticate
      client ! init.Event(TcpPackageIn(Try(Authenticated)))

      client ! PeerClosed

      expectNoMsg(100.millis)
      expectOperationTimedOut(Ping, Authenticate)
      client ! init.Event(TcpPackageIn(Try(Pong), id))
      expectNoMsg(100.millis)
    }

    "reply with OperationTimedOut if not subscribed within timeout" in new OperationTimedOutScope {
      client ! TcpPackageOut(SubscribeTo(EventStream.All), id, credentials)
      client ! init.Event(TcpPackageIn(Try(SubscribeToAllCompleted(0))))

      expectNoMsg(100.millis)
      expectOperationTimedOut(SubscribeTo(EventStream.All))

      client ! init.Event(TcpPackageIn(Try(SubscribeToAllCompleted(0)), id))

      expectNoMsg(100.millis)
    }

    "reply with OperationTimedOut if not unsubscribed within timeout" in new OperationTimedOutScope {
      sendConnected()
      client ! TcpPackageOut(SubscribeTo(EventStream.All), id, credentials)
      client ! init.Event(TcpPackageIn(Try(SubscribeToAllCompleted(0))))
      client ! init.Event(TcpPackageIn(Try(SubscribeToAllCompleted(0)), id))

      expectMsg(SubscribeToAllCompleted(0))
      expectNoMsg(operationTimeout + 100.millis)

      client ! TcpPackageOut(Unsubscribe, id, credentials)
      expectNoMsg(100.millis)
      expectOperationTimedOut(Unsubscribe)

      client ! init.Event(TcpPackageIn(Try(UnsubscribeCompleted)))
      client ! init.Event(TcpPackageIn(Try(UnsubscribeCompleted), id))
      expectNoMsg(100.millis)
    }

    "reply with OperationTimedOut for all awaiting operations" in new OperationTimedOutScope {
      sendConnected()
      client ! TcpPackageOut(SubscribeTo(EventStream.All), id, credentials)
      client ! init.Event(TcpPackageIn(Try(SubscribeToAllCompleted(0))))

      client ! TcpPackageOut(Unsubscribe, id, credentials)
      expectNoMsg(100.millis)
      expectOperationTimedOut(SubscribeTo(EventStream.All), Unsubscribe)

      client ! init.Event(TcpPackageIn(Try(SubscribeToAllCompleted(0)), id))
      client ! init.Event(TcpPackageIn(Try(UnsubscribeCompleted)))
      client ! init.Event(TcpPackageIn(Try(UnsubscribeCompleted), id))
      expectNoMsg(100.millis)
    }.pendingUntilFixed

    "bind actor to correlationId temporarily" in new TcpScope {
      val (connection, tcpConnection) = connect()
      val probe = TestProbe()
      val actor = probe.ref
      connection.tell(Ping, actor)
      val pack = expectPack
      pack.message mustEqual Success(Ping)
      val correlationId = pack.correlationId
      tcpConnection ! write(TcpPackageOut(Pong, correlationId))
      probe expectMsg Pong
      tcpConnection ! write(TcpPackageOut(Pong, correlationId))
      probe.expectNoMsg(1.second)
    }

    "unbind actor when stopped" in new TcpScope {
      val (connection, tcpConnection) = connect()

      val probe = TestProbe()
      val actor = probe.ref

      val deathProbe = TestProbe()
      deathProbe watch tcpConnection
      deathProbe watch actor
      deathProbe watch connection

      connection.tell(Ping, probe.ref)

      val req = expectPack
      req.message mustEqual Success(Ping)

      val res = TcpPackageOut(Pong, req.correlationId)
      tcpConnection ! write(res)
      probe expectMsg Pong

      system stop actor
      deathProbe.expectMsgPF() {
        case Terminated(`actor`) =>
      }

      tcpConnection ! write(res)
      probe expectNoMsg 1.second
      deathProbe expectNoMsg 1.second
    }

    "unsubscribe if not yet subscribed and unsubscribe received" in new SubscriptionScope {
      client ! Unsubscribe
      client ! init.Event(TcpPackageIn(Try(subscribeCompleted), id))
      pipeline.expectMsg(init.Command(TcpPackageOut(Unsubscribe, id, credentials)))
      client ! init.Event(TcpPackageIn(Try(UnsubscribeCompleted), id))
      expectMsg(subscribeCompleted)
      expectMsg(UnsubscribeCompleted)
      expectNoMsg(duration)
    }.pendingUntilFixed

    "not unsubscribe if not yet subscribed and client died" in new TestScope {
      val probe = TestProbe()
      client.tell(SubscribeTo(EventStream.All), probe.ref)
      system stop probe.ref
      sendConnected()
      expectNoMsg(duration)
    }

    "unsubscribe if client died" in new SubscriptionScope {
      client ! init.Event(TcpPackageIn(Try(subscribeCompleted), id))
      system stop testActor
      pipeline.expectMsg(init.Command(TcpPackageOut(Unsubscribe, id, credentials)))
    }

    "unsubscribe if not subscribed and client died" in new SubscriptionScope {
      system stop testActor
      pipeline.expectMsg(init.Command(TcpPackageOut(Unsubscribe, id, credentials)))
      expectNoMsg()
    }

    "not unsubscribe twice" in new SubscriptionScope {
      def forStream(stream: EventStream, id: Uuid, uc: Option[UserCredentials], probe: TestProbe) = {
        client ! init.Event(TcpPackageIn(Try(subscribeCompleted), id))
        client.tell(Unsubscribe, probe.ref)
        system stop probe.ref
        client.tell(Unsubscribe, probe.ref)
        pipeline.expectMsg(init.Command(TcpPackageOut(Unsubscribe, id, uc)))
        expectNoMsg(duration)
      }
    }

    "not unsubscribe twice if client died" in new SubscriptionScope {
      client ! init.Event(TcpPackageIn(Try(subscribeCompleted), id))
      expectMsg(subscribeCompleted)
      system stop testActor
      client ! Unsubscribe
      pipeline expectMsg init.Command(TcpPackageOut(Unsubscribe, id, credentials))
      pipeline.expectNoMsg(duration)
    }

    "re-subscribe after reconnected" in new SubscriptionScope {
      client ! init.Event(TcpPackageIn(Try(subscribeCompleted), id))
      client ! PeerClosed
      expectConnect()
      sendConnected()
      pipeline.expectMsg(init.Command(TcpPackageOut(subscribeTo, id, credentials)))
    }

    "not unsubscribe after reconnected" in new SubscriptionScope {
      val completed = subscribeCompleted
      client ! init.Event(TcpPackageIn(Try(completed), id))
      expectMsg(completed)
      client ! PeerClosed
      expectConnect()
      client ! Unsubscribe
      expectMsg(UnsubscribeCompleted)
      sendConnected()
      pipeline.expectNoMsg(duration)
    }.pendingUntilFixed

    "ignore subscribed while reconnecting" in new SubscriptionScope {
      client ! PeerClosed
      expectConnect()
      val completed = subscribeCompleted
      val completedEvent = init.Event(TcpPackageIn(Try(completed), id))
      client ! completedEvent
      expectNoMsg(duration)
      sendConnected()
      pipeline expectMsg init.Command(TcpPackageOut(subscribeTo, id, credentials))
      client ! completedEvent
      expectMsg(completed)
    }

    "reply with unsubscribed if connection lost while unsubscribing" in new SubscriptionScope {
      val completed = subscribeCompleted
      client ! init.Event(TcpPackageIn(Try(completed), id))
      expectMsg(completed)
      client ! Unsubscribe
      pipeline expectMsg init.Command(TcpPackageOut(Unsubscribe, id, credentials))
      client ! PeerClosed
      expectConnect()
      expectMsg(UnsubscribeCompleted)
      sendConnected()
      pipeline.expectNoMsg(duration)
    }

    "unsubscribe if event appeared and no bound operation found" in new TestScope {
      sendConnected()
      val id = randomUuid
      val eventRecord = EventRecord(EventStream.Id("streamId"), EventNumber.First, EventData("test"))
      val indexedEvent = IndexedEvent(eventRecord, Position.First)
      client ! init.Event(TcpPackageIn(Try(StreamEventAppeared(indexedEvent)), id))
      pipeline expectMsg init.Command(TcpPackageOut(Unsubscribe, id, credentials))
    }

    "use default credentials if not provided with message" in new SecurityScope {
      val x = UserCredentials("login", "password")
      ?(default = Some(x)) must beSome(x)
    }

    "use credentials that is provided with message" in new SecurityScope {
      val c = UserCredentials("login", "password")
      ?(withMessage = Some(c)) must beSome(c)
    }

    "use credentials provided with message rather then default" in new SecurityScope {
      val x1 = UserCredentials("l1", "p2")
      val x2 = UserCredentials("l2", "p2")
      ?(withMessage = Some(x1), default = Some(x2)) must beSome(x1)
    }

    "use no credentials if either not provided with message and default" in new SecurityScope {
      ?(withMessage = None, default = None) must beNone
    }

    "unsubscribe when received SubscribeCompleted but client not found" in new TestScope {
      sendConnected()
      forall(List(SubscribeToAllCompleted(0), SubscribeToStreamCompleted(0))) {
        in =>
          val pack = TcpPackageIn(Try(in))
          client ! init.Event(pack)
          pipeline expectMsg init.Command(TcpPackageOut(Unsubscribe, pack.correlationId, `credentials`))
          success
      }
    }

    "retry operation after reconnected" in new TestScope {
      sendConnected()
      client ! Authenticate
      val cmd = pipeline.expectMsgPF() {
        case x @ init.Command(TcpPackageOut(Authenticate, _, `credentials`)) => x
      }
      client ! PeerClosed
      expectConnect()
      sendConnected()

      pipeline expectMsg cmd

      client ! PeerClosed
      expectConnect()
      sendConnected()

      pipeline expectMsg cmd
    }

    "retry operation if timed out" in new TestScope {
      sendConnected()

      client ! Authenticate

      val pack = pipeline.expectMsgPF() {
        case init.Command(x @ TcpPackageOut(Authenticate, _, `credentials`)) => x
      }
      pipeline expectMsg init.Command(pack)
      pipeline expectMsg init.Command(pack)

      override def settings = super.settings.copy(operationTimeout = 100.millis)
    }.pendingUntilFixed

    "should process messages from single client in parallel" in new TestScope {
      sendConnected()

      def tell(msg: Out) = {
        client ! msg
        pipeline.expectMsgPF() {
          case init.Command(TcpPackageOut(`msg`, id, `credentials`)) => id
        }
      }

      val id1 = tell(Ping)
      val id2 = tell(Authenticate)

      id1 mustNotEqual id2

      client ! init.Event(TcpPackageIn(Try(Pong), id2))
      //      client ! init.Event(TcpPackageIn(Try(Pong), id1)) TODO this should fail, let's test it, ideally this should trigger retry
      client ! init.Event(TcpPackageIn(Try(Authenticated), id1))
    }

    "should process messages from different clients in parallel" in new TestScope {
      sendConnected()

      def tell(msg: Out, probe: TestProbe) = {
        client.tell(msg, probe.ref)
        pipeline.expectMsgPF() {
          case init.Command(TcpPackageOut(`msg`, id, `credentials`)) => id
        }
      }

      val probe1 = TestProbe()
      val probe2 = TestProbe()
      val id1 = tell(Ping, probe1)
      val id2 = tell(Authenticate, probe2)

      client ! init.Event(TcpPackageIn(Try(Pong), id2))
      //      client ! init.Event(TcpPackageIn(Try(Pong), id1)) TODO this should fail, let's test it, ideally this should trigger retry
      client ! init.Event(TcpPackageIn(Try(Authenticated), id1))
    }
  }

  abstract class TcpScope extends ActorScope {
    val (address, socket) = bind()
    val settings = Settings(address = address)

    def connect(settings: Settings = settings): (TestActorRef[ConnectionActor], ActorRef) = {
      val connection = newConnection(settings)
      val tcpConnection = newTcpConnection()
      connection -> tcpConnection
    }

    def newConnection(settings: Settings = settings) = TestActorRef(new ConnectionActor(settings))

    def newTcpConnection() = {
      expectMsgType[Connected]
      val connection = lastSender
      connection ! Register(self)
      connection
    }

    def write(x: TcpPackageOut): Write = Write(Frame.toByteString(x))
    def write(x: Out): Write = write(TcpPackageOut(x))

    def bind(address: InetSocketAddress = new InetSocketAddress(0)): (InetSocketAddress, ActorRef) = {
      IO(Tcp) ! Bind(self, address)
      val bound = expectMsgType[Bound]
      bound.localAddress -> lastSender
    }

    def expectPack = Frame.readIn(expectMsgType[Received].data)
    def expectPackOut = Frame.readOut(expectMsgType[Received].data)

    def unbind(socket: ActorRef) {
      socket ! Unbind
      expectMsg(Unbound)
    }
  }

  object Frame {

    import EventStoreFormats._

    implicit val byteOrder = ByteOrder.LITTLE_ENDIAN

    def readIn(bs: ByteString): TcpPackageIn = {
      val iterator = bs.iterator
      val length = iterator.getInt
      TcpPackageInReader.read(iterator)
    }

    def readOut(bs: ByteString): TcpPackageOut = {
      def readPack(bi: ByteIterator) = {
        import util.BytesReader
        val readMessage = MarkerByte.readMessage(bi)

        val flags = BytesReader[Flags].read(bi)
        val correlationId = BytesReader[Uuid].read(bi)
        val credentials =
          if ((flags & Flag.Auth) == 0) None
          else Some(BytesReader[UserCredentials].read(bi))

        val message = readMessage(bi)
        TcpPackageOut(message.get.asInstanceOf[Out], correlationId, credentials)
      }

      val iterator = bs.iterator
      val length = iterator.getInt
      readPack(iterator)
    }

    def toByteString(pack: TcpPackageOut): ByteString = {
      val bb = ByteString.newBuilder
      val data = util.BytesWriter[TcpPackageOut].toByteString(pack)
      bb.putInt(data.length)
      bb.append(data)
      bb.result()
    }
  }

  trait SecurityScope extends TcpScope {
    def ?(default: Option[UserCredentials] = None, withMessage: Option[UserCredentials] = None) = {
      val (connection, _) = connect(settings.copy(defaultCredentials = default))
      val message = Ping
      val envelope = withMessage match {
        case Some(x) => WithCredentials(message, x)
        case None    => message
      }
      connection ! envelope
      val pack = expectPackOut
      pack.message mustEqual message
      pack.credentials
    }
  }

  trait SubscriptionScope extends TestScope {
    val stream: EventStream = EventStream.Id("stream")
    val subscribeTo = SubscribeTo(stream)
    val subscribeCompleted = SubscribeToAllCompleted(0)

    sendConnected()
    client ! subscribeTo
    val id = pipeline.expectMsgPF() {
      case init.Command(TcpPackageOut(`subscribeTo`, id, `credentials`)) => id
    }

    override def settings = Settings(maxReconnections = 1, heartbeatInterval = 10.seconds, heartbeatTimeout = 20.seconds)
  }

  trait TestScope extends ActorScope {
    val duration = 1.second
    val credentials = settings.defaultCredentials
    val connect = Connect(settings.address, timeout = Some(settings.connectionTimeout))

    val tcp = TestProbe()
    val pipeline = TestProbe()
    val connection = TestProbe()

    val client = TestActorRef(new ConnectionActor(settings) {
      override def tcp = TestScope.this.tcp.ref
      override def newPipeline(connection: ActorRef) = TestScope.this.pipeline.ref
    })

    val init = client.underlyingActor.init

    expectConnect()

    def settings = Settings()

    def expectConnect() {
      tcp expectMsg connect
    }

    def sendConnected() {
      client.tell(Connected(settings.address, new InetSocketAddress(0)), connection.ref)
      connection expectMsg Register(pipeline.ref)
    }

    def expectNoMsgs() {
      tcp.expectNoMsg(duration)
      pipeline.expectNoMsg(duration)
      expectNoMsg(duration)
    }

    def expectTerminated() {
      val deathWatch = TestProbe()
      deathWatch watch client
      deathWatch.expectMsgPF() {
        case Terminated(`client`) => true
      }
    }

    def verifyReconnections(n: Int): Unit = if (n > 0) {
      expectConnect()
      client ! CommandFailed(connect)
      verifyReconnections(n - 1)
    }

    def expectNoConnectionFailure() {
      expectMsg(Failure(EsException(EsError.ConnectionLost)))
    }
  }

  trait OperationTimedOutScope extends TestScope {
    val operationTimeout = settings.operationTimeout
    val id = randomUuid

    def expectOperationTimedOut(x: Out, xs: Out*): Unit = {
      val msgs = (x +: xs).map { x =>
        Failure(EsException(EsError.OperationTimedOut(x)))
      }
      expectMsgAllOf(msgs: _*)
    }

    override def settings = super.settings.copy(operationTimeout = 500.millis)
  }
}
