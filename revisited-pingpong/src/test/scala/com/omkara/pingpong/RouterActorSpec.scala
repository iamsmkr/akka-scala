package com.omkara.pingpong

import akka.actor.{ ActorSystem, Props }
import akka.testkit.{ TestKit, TestActorRef, ImplicitSender, TestProbe }
import org.scalatest.{ Matchers, FlatSpecLike, BeforeAndAfterAll }
import com.typesafe.config.ConfigFactory
import scala.util.Random

class RouterActorSpec extends TestKit(ActorSystem("RouterActorSpec",
  ConfigFactory.parseString("""
      akka.loggers = ["akka.testkit.TestEventListener"]
      akka.stdout-loglevel = "OFF"
      akka.loglevel = "OFF"
    """)))
    with ImplicitSender
    with FlatSpecLike
    with Matchers
    with BeforeAndAfterAll {
  import PingPongActor._
  import RouterActor._

  trait Messages {
    val pingMessage = "ping"
    val pongMessage = "pong"

    val identifier = Random.nextInt()
  }

  trait Router extends Messages {
    val routerRef = TestActorRef[RouterActor]
    val router = routerRef.underlyingActor
  }

  trait TwoRegisteredRoutees extends Router {
    val routee1 = TestProbe()
    val routee2 = TestProbe()

    router.routees += routee1.ref
    router.routees += routee2.ref
  }

  trait ThreeRegisteredRoutees extends Router {
    val routee1 = TestProbe()
    val routee2 = TestProbe()
    val routee3 = TestProbe()

    router.routees += routee1.ref
    router.routees += routee2.ref
    router.routees += routee3.ref
  }

  override def afterAll {
    TestKit.shutdownActorSystem(system)
  }

  "RouterActor" should "register a requesting actor" in new Router {

    routerRef ! Register
    expectNoMsg

    router.routees.contains(testActor) shouldEqual true

  }

  it should "unregister a requesting actor" in new Router {

    routerRef ! Unregister
    expectNoMsg

    router.routees.isEmpty shouldEqual true

  }

  it should "assign roles" in new TwoRegisteredRoutees {

    routerRef ! AssignRoles
    expectNoMsg

    routee1.expectMsgPF() {
      case PingNow =>
        router.currentActorWithPingRole shouldEqual routee1.ref
      case PongNow =>
    }

    routee2.expectMsgPF() {
      case PingNow =>
        router.currentActorWithPingRole shouldEqual routee2.ref
      case PongNow =>
    }

  }

  it should "reset roles" in new TwoRegisteredRoutees {

    router.currentActorWithPingRole = routee1.ref

    routerRef ! ResetRoles

    routee1.expectMsg(PongNow)
    routee2.expectMsg(PingNow)

    router.currentActorWithPingRole shouldEqual routee2.ref

  }

  it should "forward PingMessage to all it's registered routees" in new TwoRegisteredRoutees {

    routee1.send(routerRef, PingMessage(pingMessage, identifier))

    routee2.expectMsg(PingMessage(pingMessage, identifier))

    router.messageIdentifier shouldEqual identifier
    router.repliesReceivedFrom.isEmpty shouldEqual true

  }

  it should "keep track of all the PongMessage replies that match currently set message identifier" in new TwoRegisteredRoutees {

    router.messageIdentifier = identifier

    routee1.send(routerRef, PongMessage(pongMessage, identifier))
    routee2.send(routerRef, PongMessage(pongMessage, identifier))

    router.repliesReceivedFrom shouldEqual Set(routee1.ref, routee2.ref)

    router.pongMessage shouldEqual pongMessage

  }

  it should "inform the pinging actor of unreachable ponging actors" in new ThreeRegisteredRoutees {

    router.currentActorWithPingRole = routee1.ref
    router.repliesReceivedFrom += routee2.ref

    val unreachable = (router.routees - router.currentActorWithPingRole) diff router.repliesReceivedFrom

    routerRef ! StopWatchEnded

    routee1.expectMsgPF() {
      case UnreachableActorException(unreachable) =>
    }

  }

  it should "inform the pinging actor of unregistered ponging actors" in new TwoRegisteredRoutees {

    val unregisteredRoutee = TestProbe()

    router.currentActorWithPingRole = routee1.ref

    router.pongMessage = pongMessage
    router.messageIdentifier = identifier

    router.repliesReceivedFrom += routee2.ref
    router.repliesReceivedFrom += unregisteredRoutee.ref

    val unregistered = router.repliesReceivedFrom diff (router.routees - router.currentActorWithPingRole)

    routerRef ! StopWatchEnded

    routee1.expectMsgPF() {
      case UnregisteredActorException(unregistered) =>
    }
    routee1.expectMsgPF() {
      case PongMessage(pongMessage, messageIdentifier) =>
    }

  }

  it should "reply pinging actor with PongMessage when received replies from all ponging actors" in new TwoRegisteredRoutees {

    router.currentActorWithPingRole = routee1.ref

    router.pongMessage = pongMessage
    router.messageIdentifier = identifier

    router.repliesReceivedFrom += routee2.ref

    routerRef ! StopWatchEnded

    routee1.expectMsg(PongMessage(pongMessage, identifier))

  }
}
