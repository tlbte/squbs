package org.squbs.unicomplex

import akka.testkit.{ImplicitSender, TestKit}
import org.scalatest._
import java.io.{FileNotFoundException, File}
import scala.concurrent.duration._
import org.scalatest.concurrent.AsyncAssertions
import scala.io.Source
import scala.concurrent._
import akka.pattern.ask
import akka.actor.{ActorSystem, ActorRef}
import scala.util.{Success, Failure, Try}
import org.squbs.unicomplex.dummyextensions.DummyExtension
import org.squbs.lifecycle.GracefulStop
import com.typesafe.config.ConfigFactory
import scala.util.Failure
import scala.util.Success

/**
 * Created by zhuwang on 2/21/14.
 */
object UnicomplexSpec {

  val dummyJarsDir = new File("unicomplex/src/test/resources/classpaths")

  val classPaths =
    if (dummyJarsDir.exists && dummyJarsDir.isDirectory) {
      dummyJarsDir.listFiles().map(_.getAbsolutePath)
    } else {
      throw new RuntimeException("[UnicomplexSpec] There is no cube to be loaded")
    }

  import collection.JavaConversions._

  val mapConfig = ConfigFactory.parseMap(
    Map(
      "squbs.actorsystem-name"    -> "UnicomplexSpec",
      "squbs." + JMX.prefixConfig -> Boolean.box(true)
    )
  )

  val boot = UnicomplexBoot(mapConfig)
    .createUsing {(name, config) => ActorSystem(name, config)}
    .scanComponents(classPaths)
    .initExtensions.start()

}

class UnicomplexSpec extends TestKit(UnicomplexSpec.boot.actorSystem) with ImplicitSender
                             with WordSpecLike with Matchers with BeforeAndAfterAll
                             with BeforeAndAfterEach with AsyncAssertions with SequentialNestedSuiteExecution {

  import UnicomplexSpec._

  implicit val timeout: akka.util.Timeout = 2.seconds

  val port = system.settings.config getInt "default-listener.bind-port"

  implicit val executionContext = system.dispatcher

  override def afterEach() {
    println("----------------------------------------------------------------------------------------")
  }

  override def beforeAll() {
    def svcReady = Try {
      Source.fromURL(s"http://127.0.0.1:$port/dummysvc/msg/hello").getLines()
    } match {
      case Success(_) => true
      case Failure(e) => println(e.getMessage); false
    }

    var retry = 5
    while (!svcReady && retry > 0) {
      Thread.sleep(1000)
      retry -= 1
    }

    if (retry == 0) throw new Exception("Starting service timeout in 5s")
  }
  
  override def afterAll() {
    system.shutdown
  }

  "UnicomplexBoot" must {

    "start all cube actors" in {
      val w = new Waiter

      system.actorSelection("/user/DummyCube").resolveOne().onComplete(result => {
        w {assert(result.isSuccess)}
        w.dismiss()
      })
      w.await()

      system.actorSelection("/user/DummyCubeSvc").resolveOne().onComplete(result => {
        w {assert(result.isSuccess)}
        w.dismiss()
      })
      w.await()

      system.actorSelection("/user/DummyCube/Appender").resolveOne().onComplete(result => {
        w {assert(result.isSuccess)}
        w.dismiss()
      })
      w.await()

      system.actorSelection("/user/DummyCube/Prepender").resolveOne().onComplete(result => {
        w {assert(result.isSuccess)}
        w.dismiss()
      })
      w.await()

      system.actorSelection("/user/DummyCubeSvc/PingPongPlayer").resolveOne().onComplete(result => {
        w {assert(result.isSuccess)}
        w.dismiss()
      })
      w.await()
    }

    "start all services" in {
      assert(boot.services.size == 2)

      assert(Source.fromURL(s"http://127.0.0.1:$port/dummysvc/msg/hello").mkString equals "^hello$")
      assert(Source.fromURL(s"http://127.0.0.1:$port/pingpongsvc/ping").mkString equals "Pong")
      assert(Source.fromURL(s"http://127.0.0.1:$port/pingpongsvc/pong").mkString equals "Ping")
    }

    "preInit, init and postInit all extenstions" in {
      assert(boot.extensions.size == 2)

      assert(boot.extensions.forall(_._3.isInstanceOf[DummyExtension]))
      assert(boot.extensions(0)._3.asInstanceOf[DummyExtension].state == "AstartpreInitinitpostInit")
      assert(boot.extensions(1)._3.asInstanceOf[DummyExtension].state == "BstartpreInitinitpostInit")
    }
  }

  "CubeSupervisor" must {

    "get init reports from cube actors" in {
      system.actorSelection("/user/CubeA") ! CheckInitStatus
      val report = expectMsgType[(InitReports, Boolean)]._1
      report.state should be(Active)
      report.reports.size should be(2)
    }

    "get init reports from cube actors even if the actor failed in init" in {
      system.actorSelection("/user/InitFail") ! CheckInitStatus
      val report = expectMsgType[(InitReports, Boolean)]._1
      report.state should be(Failed)
      report.reports.size should be(1)
    }

    "deal with the situation that cube actors are not able to send the reports" in {
      system.actorSelection("/user/InitBlock") ! CheckInitStatus
      val report = expectMsgType[(InitReports, Boolean)]._1
      report.state should be(Initializing)
      report.reports.size should be(1)
    }
  }

  "UniComplex" must {

    "get cube init reports" in {
      Unicomplex(system).uniActor ! ReportStatus
      val (systemState, cubes) = expectMsgType[(LifecycleState, Map[ActorRef, (CubeRegistration, Option[InitReports])])]
      systemState should be (Failed)
      val cubeAReport = cubes.values.find(_._1.name == "CubeA").flatMap(_._2)
      cubeAReport should not be (None)
      assert(cubeAReport.get.state == Active)
      val cubeBReport = cubes.values.find(_._1.name == "CubeB").flatMap(_._2)
      cubeBReport should not be (None)
      cubeBReport.get.state should be (Active)
      val initFailReport = cubes.values.find(_._1.name == "InitFail").flatMap(_._2)
      initFailReport should not be (None)
      initFailReport.get.state should be (Failed)
      val initBlockReport = cubes.values.find(_._1.name == "InitBlock").flatMap(_._2)
      initBlockReport should not be (None)
      initBlockReport.get.state should be (Initializing)
    }

    "stop a single cube without affect other cubes" in {
      assert(Source.fromURL(s"http://127.0.0.1:$port/pingpongsvc/ping").mkString equals "Pong")
      assert(Source.fromURL(s"http://127.0.0.1:$port/pingpongsvc/pong").mkString equals "Ping")
      Unicomplex(system).uniActor ! StopCube("org.squbs.unicomplex.test.DummyCubeSvc")
      expectMsg(Ack)

      val w = new Waiter
      system.actorSelection("/user/DummyCubeSvc").resolveOne().onComplete(result => {
        w {assert(result.isFailure)}
        w.dismiss()
      })
      w.await()

      assert(Source.fromURL(s"http://127.0.0.1:$port/dummysvc/msg/hello").mkString equals "^hello$")
      intercept[FileNotFoundException]{
        Source.fromURL(s"http://127.0.0.1:$port/pingpongsvc/ping").mkString
      }
      intercept[FileNotFoundException]{
        Source.fromURL(s"http://127.0.0.1:$port/pingpongsvc/pong").mkString
      }
    }

    "not mess up if stop a stopped cube" in {
      assert(Source.fromURL(s"http://127.0.0.1:$port/dummysvc/msg/hello").mkString equals "^hello$")
      intercept[FileNotFoundException]{
        Source.fromURL(s"http://127.0.0.1:$port/pingpongsvc/ping").mkString
      }
      intercept[FileNotFoundException]{
        Source.fromURL(s"http://127.0.0.1:$port/pingpongsvc/pong").mkString
      }

      Unicomplex(system).uniActor ! StopCube("org.squbs.unicomplex.test.DummyCubeSvc")
      expectMsg(Ack)

      val w = new Waiter
      system.actorSelection("/user/DummyCubeSvc").resolveOne().onComplete(result => {
        w {assert(result.isFailure)}
        w.dismiss()
      })
      w.await()

      assert(Source.fromURL(s"http://127.0.0.1:$port/dummysvc/msg/hello").mkString equals "^hello$")
      intercept[FileNotFoundException]{
        Source.fromURL(s"http://127.0.0.1:$port/pingpongsvc/ping").mkString
      }
      intercept[FileNotFoundException]{
        Source.fromURL(s"http://127.0.0.1:$port/pingpongsvc/pong").mkString
      }
    }

    "start a single cube correctly" in {
      intercept[FileNotFoundException]{
        Source.fromURL(s"http://127.0.0.1:$port/pingpongsvc/ping").mkString
      }

      intercept[FileNotFoundException]{
        Source.fromURL(s"http://127.0.0.1:$port/pingpongsvc/pong").mkString
      }

      Unicomplex(system).uniActor ! StartCube("org.squbs.unicomplex.test.DummyCubeSvc",
        boot.initInfoMap, boot.listenerAliases)
      expectMsg(Ack)

      Thread.sleep(1000)

      val w = new Waiter
      system.actorSelection("/user/DummyCubeSvc").resolveOne().onComplete(result => {
        w {assert(result.isSuccess)}
        w.dismiss()
      })
      w.await()

      assert(Source.fromURL(s"http://127.0.0.1:$port/dummysvc/msg/hello").mkString equals "^hello$")
      assert(Source.fromURL(s"http://127.0.0.1:$port/pingpongsvc/ping").mkString equals "Pong")
      assert(Source.fromURL(s"http://127.0.0.1:$port/pingpongsvc/pong").mkString equals "Ping")
    }

    "not mess up if start a running cube" in {
      Unicomplex(system).uniActor ! StartCube("org.squbs.unicomplex.test.DummyCubeSvc", boot.initInfoMap,
        boot.listenerAliases)

      expectMsg(Ack)

      val w = new Waiter
      system.actorSelection("/user/DummyCubeSvc").resolveOne().onComplete(result => {
        w {assert(result.isSuccess)}
        w.dismiss()
      })
      w.await()

      assert(Source.fromURL(s"http://127.0.0.1:$port/dummysvc/msg/hello").mkString equals "^hello$")
      assert(Source.fromURL(s"http://127.0.0.1:$port/pingpongsvc/ping").mkString equals "Pong")
      assert(Source.fromURL(s"http://127.0.0.1:$port/pingpongsvc/pong").mkString equals "Ping")
    }

    "not mess up if stop and start a cube contains actors and services simultaneously" in {
      Unicomplex(system).uniActor ! StopCube("org.squbs.unicomplex.test.DummyCubeSvc")
      Unicomplex(system).uniActor ! StartCube("org.squbs.unicomplex.test.DummyCubeSvc", boot.initInfoMap,
        boot.listenerAliases)
      expectMsg(Ack)
      expectMsg(Ack)

      def svcReady = Try {
        assert(Source.fromURL(s"http://127.0.0.1:$port/dummysvc/msg/hello").mkString equals "^hello$")
        assert(Source.fromURL(s"http://127.0.0.1:$port/pingpongsvc/ping").mkString equals "Pong")
        assert(Source.fromURL(s"http://127.0.0.1:$port/pingpongsvc/pong").mkString equals "Ping")
      } match {
        case Success(_) => true
        case Failure(e) => println(e.getMessage); false
      }

      var retry = 5
      while (!svcReady && retry > 0) {
        Thread.sleep(1000)
        retry -= 1
      }

      if (retry == 0) throw new Exception("service timeout in 5s")
    }

    "not mess up if stop and start a cube contains actors only simultaneously" in {
      Unicomplex(system).uniActor ! StopCube("org.squbs.unicomplex.test.DummyCube")
      Unicomplex(system).uniActor ! StartCube("org.squbs.unicomplex.test.DummyCube", boot.initInfoMap,
          boot.listenerAliases)
      expectMsg(Ack)
      expectMsg(Ack)

      def svcReady = Try {
        assert(Source.fromURL(s"http://127.0.0.1:$port/dummysvc/msg/hello").mkString equals "^hello$")
        assert(Source.fromURL(s"http://127.0.0.1:$port/pingpongsvc/ping").mkString equals "Pong")
        assert(Source.fromURL(s"http://127.0.0.1:$port/pingpongsvc/pong").mkString equals "Ping")
      } match {
        case Success(_) => true
        case Failure(e) => println(e.getMessage); false
      }

      var retry = 5
      while (!svcReady && retry > 0) {
        Thread.sleep(1000)
        retry -= 1
      }

      if (retry == 0) throw new Exception("service timeout in 5s")
    }

    "not mess up if stop and start a cube contains services only simultaneously" in {
      Unicomplex(system).uniActor ! StopCube("org.squbs.unicomplex.test.DummySvc")
      Unicomplex(system).uniActor ! StartCube("org.squbs.unicomplex.test.DummySvc", boot.initInfoMap,
        boot.listenerAliases)
      expectMsg(Ack)
      expectMsg(Ack)

      assert(Source.fromURL(s"http://127.0.0.1:$port/dummysvc/msg/hello").mkString equals "^hello$")
      assert(Source.fromURL(s"http://127.0.0.1:$port/pingpongsvc/ping").mkString equals "Pong")
      assert(Source.fromURL(s"http://127.0.0.1:$port/pingpongsvc/pong").mkString equals "Ping")
    }

    "not mess up if stop all cubes simultaneously" in {
      Unicomplex(system).uniActor ! StopCube("org.squbs.unicomplex.test.DummySvc")
      Unicomplex(system).uniActor ! StopCube("org.squbs.unicomplex.test.DummyCube")
      Unicomplex(system).uniActor ! StopCube("org.squbs.unicomplex.test.DummyCubeSvc")
      expectMsg(Ack)
      expectMsg(Ack)
      expectMsg(Ack)

      intercept[FileNotFoundException]{
        Source.fromURL(s"http://127.0.0.1:$port/dummysvc/msg/hello").mkString
      }
      intercept[FileNotFoundException]{
        Source.fromURL(s"http://127.0.0.1:$port/pingpongsvc/ping").mkString
      }
      intercept[FileNotFoundException]{
        Source.fromURL(s"http://127.0.0.1:$port/pingpongsvc/pong").mkString
      }
    }

    "not mess up if start all cubes simultaneously" in {
      Unicomplex(system).uniActor ! StartCube("org.squbs.unicomplex.test.DummySvc", boot.initInfoMap,
        boot.listenerAliases)
      Unicomplex(system).uniActor ! StartCube("org.squbs.unicomplex.test.DummyCube", boot.initInfoMap,
        boot.listenerAliases)
      Unicomplex(system).uniActor ! StartCube("org.squbs.unicomplex.test.DummyCubeSvc", boot.initInfoMap,
        boot.listenerAliases)
      expectMsg(Ack)
      expectMsg(Ack)
      expectMsg(Ack)

      def svcReady = Try {
        assert(Source.fromURL(s"http://127.0.0.1:$port/dummysvc/msg/hello").mkString equals "^hello$")
        assert(Source.fromURL(s"http://127.0.0.1:$port/pingpongsvc/ping").mkString equals "Pong")
        assert(Source.fromURL(s"http://127.0.0.1:$port/pingpongsvc/pong").mkString equals "Ping")
      } match {
        case Success(_) => true
        case Failure(e) => println(e.getMessage); false
      }

      var retry = 5
      while (!svcReady && retry > 0) {
        Thread.sleep(1000)
        retry -= 1
      }

      if (retry == 0) throw new Exception("service timeout in 5s")
    }
  }
}
