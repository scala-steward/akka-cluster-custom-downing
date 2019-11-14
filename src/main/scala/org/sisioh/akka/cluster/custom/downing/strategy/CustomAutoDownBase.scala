/**
  * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
  *
  * 2016- Modified by Yusuke Yasuda
  * 2019- Modified by Junichi Kato
  * The original source code can be found here.
  * https://github.com/akka/akka/blob/master/akka-cluster/src/main/scala/akka/cluster/AutoDown.scala
  */
package org.sisioh.akka.cluster.custom.downing.strategy

import akka.actor.{ Actor, Address, Cancellable, Scheduler }
import akka.cluster.ClusterEvent._
import akka.cluster.MemberStatus.{ Down, Exiting }
import akka.cluster._

import scala.concurrent.duration.{ Duration, FiniteDuration }

object CustomDowning {
  private[downing] case class UnreachableTimeout(member: Member)
  private[downing] val skipMemberStatus: Set[MemberStatus] = Set[MemberStatus](Down, Exiting)
}

abstract class CustomAutoDownBase(autoDownUnreachableAfter: FiniteDuration) extends Actor {
  autoDownUnreachableAfter.isFinite
  import CustomDowning._

  protected def selfAddress: Address

  protected def down(node: Address): Unit

  protected def downOrAddPending(member: Member): Unit

  protected def downOrAddPendingAll(members: Members): Unit

  protected def scheduler: Scheduler

  import context.dispatcher

  private var scheduledUnreachable: MemberCancellables = MemberCancellables.empty
  private var pendingUnreachable: Members              = Members.empty
  private var unstableUnreachable: Members             = Members.empty

  override def postStop(): Unit = {
    scheduledUnreachable.cancelAll()
    super.postStop()
  }

  override def receive: Receive = receiveEvent orElse predefinedReceiveEvent

  protected def receiveEvent: Receive

  private def predefinedReceiveEvent: Receive = {
    case state: CurrentClusterState =>
      initialize(state)
      state.unreachable foreach unreachableMember

    case UnreachableTimeout(member) =>
      if (scheduledUnreachable contains member) {
        scheduledUnreachable -= member
        if (scheduledUnreachable.isEmpty) {
          unstableUnreachable += member
          downOrAddPendingAll(unstableUnreachable)
          unstableUnreachable = Members.empty
        } else {
          unstableUnreachable += member
        }
      }

    case _: ClusterDomainEvent =>
  }

  protected def initialize(state: CurrentClusterState): Unit = {}

  protected def onMemberDowned(member: Member): Unit = {}

  protected def onMemberRemoved(member: Member, previousStatus: MemberStatus): Unit = {}

  protected def onLeaderChanged(leader: Option[Address]): Unit = {}

  protected def onRoleLeaderChanged(role: String, leader: Option[Address]): Unit = {}

  protected def unreachableMember(m: Member): Unit =
    if (!skipMemberStatus(m.status) && !scheduledUnreachable.contains(m))
      scheduleUnreachable(m)

  private def scheduleUnreachable(m: Member): Unit =
    if (autoDownUnreachableAfter == Duration.Zero)
      downOrAddPending(m)
    else {
      val task = scheduler.scheduleOnce(autoDownUnreachableAfter, self, UnreachableTimeout(m))
      scheduledUnreachable += (m -> task)
    }

  protected def remove(member: Member): Unit = {
    scheduledUnreachable.cancel(member)
    scheduledUnreachable -= member
    pendingUnreachable -= member
    unstableUnreachable -= member
  }

  protected def scheduledUnreachableMembers: MemberCancellables =
    scheduledUnreachable

  protected def pendingUnreachableMembers: Members = pendingUnreachable

  protected def pendingAsUnreachable(member: Member): Unit = pendingUnreachable += member

  protected def downPendingUnreachableMembers(): Unit = {
    val (head, tail) = pendingUnreachable.splitHeadAndTail
    head.foreach { member =>
      down(member.address)
    }
    pendingUnreachable = tail
  }

  protected def unstableUnreachableMembers: Members = unstableUnreachable
}
