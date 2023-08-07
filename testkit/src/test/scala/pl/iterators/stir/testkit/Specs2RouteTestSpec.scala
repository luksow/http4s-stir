package pl.iterators.stir.testkit

import cats.effect.IO
import cats.effect.unsafe.IORuntime
import org.http4s.{ Header, Response }
import org.typelevel.ci.CIString
import pl.iterators.stir.server.Directives._
import pl.iterators.stir.server._
import org.http4s.Status._
import fs2._
import org.http4s.Method.{ GET, PUT }
import org.specs2.mutable.Specification

class Specs2RouteTestSpec extends Specification with Specs2RouteTest {
  override implicit val runtime: IORuntime = IORuntime.global

  "The ScalatestRouteTest should support" should {

    "the most simple and direct route test" in {
      Get() ~> complete(Response[IO]()) ~> { rr => rr.response.status } shouldEqual Response[IO]().status
    }

    "a test using a directive and some checks" in {
      val pinkHeader = Header.Raw(CIString("Fancy"), "pink")
      Get() ~> addHeader(pinkHeader) ~> {
        respondWithHeader(pinkHeader) {
          complete("abc")
        }
      } ~> check {
        status shouldEqual Ok
        responseEntity.through(text.utf8.decode).compile.string should evaluateTo("abc")
        header("Fancy") shouldEqual Some(pinkHeader)
      }
    }

    "proper rejection collection" in {
      Post("/abc", "content") ~> {
        (get | put) {
          complete("naah")
        }
      } ~> check {
        rejections shouldEqual List(MethodRejection(GET), MethodRejection(PUT))
      }
    }

//    "separation of route execution from checking" in {
//      val pinkHeader = RawHeader("Fancy", "pink")
//
//      case object Command
//      val service = TestProbe()
//      val handler = TestProbe()
//      implicit def serviceRef: ActorRef = service.ref
//      implicit val askTimeout: Timeout = 1.second
//
//      val result =
//        Get() ~> pinkHeader ~> {
//          respondWithHeader(pinkHeader) {
//            complete(handler.ref.ask(Command).mapTo[String])
//          }
//        } ~> runRoute
//
//      handler.expectMsg(Command)
//      handler.reply("abc")
//
//      check {
//        status shouldEqual OK
//        responseEntity shouldEqual HttpEntity(ContentTypes.`text/plain(UTF-8)`, "abc")
//        header("Fancy") shouldEqual Some(pinkHeader)
//      }(result)
//    }

    "failing the test inside the route" in {

      val route = get {
        failure("BOOM")
        complete(Response[IO]())
      }

      {
        Get() ~> route
      } must throwA[org.specs2.execute.FailureException]
    }

    "failing an assertion inside the route" in {
      val route = get {
        throw new AssertionError("test")
      }

      {
        Get() ~> route
      } must throwA[java.lang.AssertionError]
    }

    "internal server error" in {

      val route = get {
        throw new RuntimeException("BOOM")
      }

      Get().~>(route).~>(check {
        status shouldEqual InternalServerError
      })
    }
  }
}
