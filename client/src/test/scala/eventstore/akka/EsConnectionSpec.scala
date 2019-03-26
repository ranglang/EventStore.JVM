package eventstore
package akka

import scala.util.Success
import _root_.akka.actor.Status.Failure
import testutil._
import TestData._

class EsConnectionSpec extends ActorSpec {

  "EventStoreConnection.future" should {
    "write events" in new TestScope {
      verifyOutIn(writeEvents, writeEventsCompleted)
    }

    "delete stream" in new TestScope {
      verifyOutIn(deleteStream, deleteStreamCompleted)
    }

    "transaction start" in new TestScope {
      verifyOutIn(transactionStart, transactionStartCompleted)
    }

    "transaction write" in new TestScope {
      verifyOutIn(transactionWrite, transactionWriteCompleted)
    }

    "transaction commit" in new TestScope {
      verifyOutIn(transactionCommit, transactionCommitCompleted)
    }

    "read event" in new TestScope {
      verifyOutIn(readEvent, readEventCompleted)
    }

    "read stream events" in new TestScope {
      verifyOutIn(readStreamEvents, readStreamEventsCompleted)
    }

    "read all events" in new TestScope {
      verifyOutIn(readAllEvents, readAllEventsCompleted)
    }

    "subscribe to" in new TestScope {
      verifyOutIn(subscribeTo, subscribeToStreamCompleted)
    }

    "set stream metadata" in new TestScope {
      val content = Content(byteStringInt8(1, 2, 3))
      val sId = streamId.metadata
      val future = connection.setStreamMetadata(streamId, content)

      expectMsgPF() {
        case WriteEvents(`sId`, List(EventData(SystemEventType.metadata, _, `content`, _)), ExpectedVersion.Any, true) => true
      }
      lastSender ! WriteEventsCompleted()
      future.await_ must beNone
    }

    "get stream metadata" in new GetMetadataScope {
      val content = Content(byteStringInt8(1, 2, 3))
      val metadata = EventRecord(streamId.metadata, EventNumber.First, EventData(SystemEventType.metadata, data = content))
      getStreamMetadata(ReadEventCompleted(metadata)) mustEqual content
    }

    "get empty metadata when stream not found" in new GetMetadataScope {
      getStreamMetadata(failure(StreamNotFoundException(EventStream.Id("test")))) mustEqual Content.Empty
    }

    "get empty metadata when stream deleted" in new GetMetadataScope {
      getStreamMetadata(failure(new StreamDeletedException("test"))) mustEqual Content.Empty
    }

    "throw exception if non metadata event received" in new GetMetadataScope {
      val event = EventRecord(streamId, EventNumber.First, EventData("test", data = Content.Empty))
      getStreamMetadata(ReadEventCompleted(event)) must throwA(NonMetadataEventException(event))
    }

    "create persistent subscription" in new TestScope {
      verifyOutIn(psCreate, psCreateCompleted)
    }

    "update persistent subscription" in new TestScope {
      verifyOutIn(psUpdate, psUpdateCompleted)
    }

    "delete persistent subscription" in new TestScope {
      verifyOutIn(psDelete, psDeleteCompleted)
    }
  }

  private trait TestScope extends ActorScope {

    val streamId = EventStream.Id("streamId")
    val events = Seq(EventData("test"))
    val connection = new EsConnection(testActor, system)

    def verifyOutIn[OUT <: Out, IN <: In](out: OUT, in: In)(implicit outIn: ClassTags[OUT, IN]): Unit = {
      val future = connection(out)(outIn)
      expectMsg(out)
      future.value must beNone
      lastSender ! in
      future.value mustEqual Some(Success(in))
    }
  }

  private trait GetMetadataScope extends TestScope {
    def getStreamMetadata(reply: Any): Content = {
      val future = connection.getStreamMetadata(streamId)
      expectMsg(ReadEvent.StreamMetadata(streamId.metadata))
      lastSender ! reply
      future.await_
    }

    def failure(x: EsException) = Failure(x)
  }
}