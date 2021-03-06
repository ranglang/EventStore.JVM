package eventstore
package core
package cluster

import java.net.InetSocketAddress

@SerialVersionUID(1L)
final case class ClusterInfo(serverAddress: InetSocketAddress, members: List[MemberInfo]) {
  lazy val bestNode: Option[MemberInfo] = {
    val xs = members.filter { x => x.isAlive && x.state.isAllowedToConnect }
    if (xs.isEmpty) None else Some(xs.maxBy(_.state))
  }
}