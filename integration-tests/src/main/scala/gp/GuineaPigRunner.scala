package gp

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.stream.ActorMaterializer
import korolev.akkahttp._
import korolev.blazeServer._
import korolev.execution._
import korolev.state.javaSerialization._
import org.openqa.selenium.remote.DesiredCapabilities
import tools._

import scala.concurrent.duration._
import scala.concurrent.{Await, Future}

object GuineaPigRunner extends App {

  val genericCaps = Seq(
    Caps(() => DesiredCapabilities.chrome)(
      "platform" -> "Windows 7",
      "version" -> "55.0",
      "chromedriverVersion" -> "2.27"
    ),
    Caps(() => DesiredCapabilities.edge)(
      "platform" -> "Windows 10",
      "version" -> "14.14393"
    ),
    Caps(() => DesiredCapabilities.internetExplorer)(
      "platform" -> "Windows 7",
      "version" -> "10.0"
    ),
    Caps(() => DesiredCapabilities.internetExplorer)(
      "platform" -> "Windows 7",
      "version" -> "11"
    ),
    Caps(() => DesiredCapabilities.firefox)(
      "platform" -> "Linux",
      "version" -> "45.0",
      "seleniumVersion" -> "2.53.0"
    ),
    Caps(() => DesiredCapabilities.android)(
      "deviceName" ->  "Android Emulator" ,
      "deviceOrientation" ->   "portrait" ,
      "browserName" ->   "Browser" ,
      "platformVersion" -> "5.1" ,
      "platformName" ->  "Android"
    ),
    Caps(() => DesiredCapabilities.safari)(
      "platform" -> "OS X 10.11",
      "version" -> "10.0"
    ),
    Caps(() => DesiredCapabilities.iphone)(
      "appiumVersion" -> "1.5.3",
      "deviceName" -> "iPhone 6 Simulator",
      "deviceOrientation" -> "portrait",
      "platformVersion" -> "9.3",
      "platformName" -> "iOS",
      "browserName" -> "Safari",
      "autoAcceptAlerts" -> "true"
    )
  )

  val servers = List(
    (scenario: () => Boolean) => {
      println("Starting Blaze server")
      val service = blazeService[Future, GuineaPigService.State, Any].from(GuineaPigService.service)
      val config = BlazeServerConfig(port = 8000, doNotBlockCurrentThread = true)
      val server = korolev.blazeServer.runServer(service, config)
      Future {
        val result = scenario()
        server.close()
        println("Blaze server shutting down")
        result
      }
    },
    (scenario: () => Boolean) => {
      println("Starting Akka-http server")
      implicit val actorSystem = ActorSystem()
      implicit val materializer = ActorMaterializer()
      val route = akkaHttpService(GuineaPigService.service).apply(AkkaHttpServerConfig())
      Http().bindAndHandle(route, "localhost", 8000).map { server =>
        val result = scenario()
        println("Akka-http server shutting down")
        server.unbind()
        result
      }
    }
  )

  val appUrl = "http://localhost:8000"

  val runScenario = () => {
    val resultFutures = GuineaPigScenarios
      .all
      .map(_.run(genericCaps:_*))
    val resultFuture = Future
      .sequence[Boolean, Seq](resultFutures)
      .map(_.reduce(_ && _))
    Await.result(resultFuture, 1.hour)
  }


  def runSerial(acc: Boolean, futures: List[(() => Boolean) => Future[Boolean]]): Future[Boolean] = futures match {
    case Nil => Future.successful(acc)
    case x :: xs => x(runScenario).flatMap { result =>
      runSerial(acc && result, xs)
    }
  }

  runSerial(acc = true, servers) foreach { result =>
    if (result) System.exit(0)
    else System.exit(1)
  }
}
