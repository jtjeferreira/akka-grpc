/*
 * Copyright (C) 2019 Lightbend Inc. <https://www.lightbend.com>
 */

package example.myapp.helloworld

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.HttpEntity.{ Chunked, LastChunk }
import akka.http.scaladsl.model.headers.RawHeader
import akka.http.scaladsl.model.{ HttpMethods, HttpRequest, HttpResponse, StatusCodes }
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.Sink
import example.myapp.helloworld.grpc.{ GreeterService, GreeterServiceHandlerFactory }
import io.grpc.Status
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.time.Span
import org.scalatest.{ BeforeAndAfterAll, Matchers, WordSpec }

import scala.concurrent.Await
import scala.concurrent.duration._

class ErrorReportingSpec extends WordSpec with Matchers with ScalaFutures with BeforeAndAfterAll {
  implicit val sys = ActorSystem()
  override implicit val patienceConfig = PatienceConfig(5.seconds, Span(100, org.scalatest.time.Millis))

  "A gRPC server" should {
    implicit val mat = ActorMaterializer()

    val handler = GreeterServiceHandlerFactory.create(new GreeterServiceImpl(mat), mat, sys)
    val binding = {
      import akka.http.javadsl.{ ConnectHttp, Http, HttpConnectionContext, UseHttp2 }

      Http(sys)
        .bindAndHandleAsync(
          handler,
          // We test responding to invalid requests with HTTP/1.1 since
          // we don't have a raw HTTP/2 client available to construct invalid
          // HTTP/2 requests.
          ConnectHttp.toHost("127.0.0.1", 0, UseHttp2.never),
          mat)
        .toCompletableFuture
        .get
    }

    "respond with an 'unimplemented' gRPC error status when calling an unknown method" in {
      val request =
        HttpRequest(uri = s"http://localhost:${binding.localAddress.getPort}/${GreeterService.name}/UnknownMethod")
      val response = Http().singleRequest(request).futureValue

      response.status should be(StatusCodes.OK)
      allHeaders(response) should contain(RawHeader("grpc-status", Status.Code.UNIMPLEMENTED.value().toString))
    }

    "respond with an 'internal' gRPC error status when calling an method without a request body" in {
      val request = HttpRequest(
        method = HttpMethods.POST,
        uri = s"http://localhost:${binding.localAddress.getPort}/${GreeterService.name}/SayHello")
      val response = Http().singleRequest(request).futureValue

      response.status should be(StatusCodes.OK)
      allHeaders(response) should contain(RawHeader("grpc-status", Status.Code.INTERNAL.value().toString))
    }

    def allHeaders(response: HttpResponse) =
      response.entity match {
        case Chunked(_, chunks) =>
          chunks.runWith(Sink.last).futureValue match {
            case LastChunk(_, trailingHeaders) => response.headers ++ trailingHeaders
            case _                             => response.headers
          }
        case _ =>
          response.headers
      }
  }

  override def afterAll: Unit =
    Await.result(sys.terminate(), 5.seconds)
}
