package com.wavesplatform.network

import java.net.{InetSocketAddress, NetworkInterface}
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

import com.wavesplatform.Version
import com.wavesplatform.mining.Miner
import com.wavesplatform.settings._
import com.wavesplatform.state2.reader.StateReader
import io.netty.bootstrap.{Bootstrap, ServerBootstrap}
import io.netty.channel._
import io.netty.channel.group.{ChannelGroup, ChannelMatchers}
import io.netty.channel.local.{LocalAddress, LocalChannel, LocalServerChannel}
import io.netty.channel.nio.NioEventLoopGroup
import io.netty.channel.socket.nio.{NioServerSocketChannel, NioSocketChannel}
import scorex.network.message.{BasicMessagesRepo, MessageSpec}
import scorex.transaction._
import scorex.utils.{ScorexLogging, Time}
import scorex.wallet.Wallet

import scala.collection.JavaConverters._
import scala.concurrent.duration._

class NetworkServer(
    chainId: Char,
    settings: WavesSettings,
    history: History,
    checkpoints: CheckpointService,
    blockchainUpdater: BlockchainUpdater,
    time: Time,
    stateReader: StateReader,
    utxStorage: UnconfirmedTransactionsStorage,
    txHandler: NewTransactionHandler,
    peerDatabase: PeerDatabase,
    wallet: Wallet,
    allChannels: ChannelGroup,
    peerInfo: ConcurrentHashMap[Channel, PeerInfo]) extends ScorexLogging {

  private val bossGroup = new NioEventLoopGroup()
  private val workerGroup = new NioEventLoopGroup()
  private val handshake =
    Handshake(Constants.ApplicationName + chainId, Version.VersionTuple, settings.networkSettings.nodeName,
      settings.networkSettings.nonce, settings.networkSettings.declaredAddress)

  private val scoreObserver = new RemoteScoreObserver(
      settings.synchronizationSettings.scoreTTL,
      history.lastBlockIds(settings.synchronizationSettings.maxRollback))

  private val allLocalInterfaces = (for {
    ifc <- NetworkInterface.getNetworkInterfaces.asScala
    ip <- ifc.getInterfaceAddresses.asScala
  } yield new InetSocketAddress(ip.getAddress, settings.networkSettings.port)).toSet

  private val discardingHandler = new DiscardingHandler
  private val specs: Map[Byte, MessageSpec[_ <: AnyRef]] = (BasicMessagesRepo.specs ++ TransactionalMessagesRepo.specs).map(s => s.messageCode -> s).toMap
  private val messageCodec = new MessageCodec(specs)

  private val network = new Network {
    override def requestExtension(localScore: BigInt) = broadcast(LocalScoreChanged(localScore))
    override def broadcast(msg: AnyRef, except: Option[Channel]) = doBroadcast(msg, except)
  }

  private val miner = new Miner(history, stateReader, utxStorage, wallet.privateKeyAccounts(), time,
    settings.blockchainSettings, b => writeToLocalChannel(BlockForged(b)))

  private val utxPoolSynchronizer = new UtxPoolSynchronizer(txHandler, network)
  private val localScorePublisher = new LocalScorePublisher(msg => doBroadcast(msg))

  private val coordinatorExecutor = new DefaultEventLoop
  private val coordinator = new Coordinator(checkpoints, history, blockchainUpdater, time, stateReader, utxStorage,
    settings.blockchainSettings, settings.checkpointsSettings.publicKey, miner)

  private val address = new LocalAddress("local-events-channel")
  private val localServerGroup = new DefaultEventLoopGroup()
  private val localServer = new ServerBootstrap()
    .group(localServerGroup)
    .channel(classOf[LocalServerChannel])
    .childHandler(new ChannelInitializer[LocalChannel] {
      override def initChannel(ch: LocalChannel) = ch.pipeline()
        .addLast(utxPoolSynchronizer, scoreObserver, localScorePublisher)
        .addLast(coordinatorExecutor, coordinator)
    })

  localServer.bind(address).sync()

  private val localClientGroup = new DefaultEventLoopGroup()
  private val localClientChannel = new Bootstrap()
    .group(localClientGroup)
    .channel(classOf[LocalChannel])
    .handler(new ChannelInitializer[LocalChannel] {
      override def initChannel(ch: LocalChannel) = {}
    })
    .connect(address)
    .channel()

  log.info(s"${id(localClientChannel)} Local channel opened")

  private val peerUniqueness = new ConcurrentHashMap[PeerKey, Channel]()

  private val serverHandshakeHandler =
    new HandshakeHandler.Server(handshake, peerDatabase, peerInfo, peerUniqueness, blacklist)

  private val serverChannel = new ServerBootstrap()
    .group(bossGroup, workerGroup)
    .channel(classOf[NioServerSocketChannel])
    .childHandler(new LegacyChannelInitializer(
      settings, history, peerDatabase, serverHandshakeHandler, discardingHandler, messageCodec, utxPoolSynchronizer,
      scoreObserver, localScorePublisher, coordinator, coordinatorExecutor, blacklist
    ))
    .bind(settings.networkSettings.port)
    .channel()

  private val outgoingChannelCount = new AtomicInteger(0)
  private val channels = new ConcurrentHashMap[InetSocketAddress, Channel]

  private val clientHandshakeHandler =
    new HandshakeHandler.Client(handshake, peerDatabase, peerInfo, peerUniqueness, blacklist)

  private val bootstrap = new Bootstrap()
    .group(workerGroup)
    .channel(classOf[NioSocketChannel])
    .handler(new LegacyChannelInitializer(
      settings, history, peerDatabase, clientHandshakeHandler, discardingHandler, messageCodec, utxPoolSynchronizer,
      scoreObserver, localScorePublisher, coordinator, coordinatorExecutor, blacklist
    ))

  workerGroup.scheduleWithFixedDelay(1.second, 5.seconds) {
    if (outgoingChannelCount.get() < settings.networkSettings.maxOutboundConnections) {
      peerDatabase.getRandomPeer(allLocalInterfaces ++ channels.keySet().asScala).foreach(connect)
    }
  }

  def connect(remoteAddress: InetSocketAddress): Unit =
    channels.computeIfAbsent(remoteAddress, _ => {
      outgoingChannelCount.incrementAndGet()
      val chan = bootstrap.connect(remoteAddress).channel()
      allChannels.add(chan)
      log.debug(s"${id(chan)} Connecting to $remoteAddress")
      chan.closeFuture().addListener { (chf: ChannelFuture) =>
        val remainingOutgoingChannelCount = outgoingChannelCount.decrementAndGet()
        log.debug(s"${id(chf.channel)} Connection to $remoteAddress closed, $remainingOutgoingChannelCount channel(s) remaining")
        allChannels.remove(chf.channel())
        channels.remove(remoteAddress, chf.channel())
      }
      chan
    })

  def writeToLocalChannel(message: AnyRef): Unit = localClientChannel.writeAndFlush(message)

  private def doBroadcast(message: AnyRef, except: Option[Channel] = None): Unit = {
    log.trace(s"Broadcasting $message to ${allChannels.size()} channels${except.fold("")(c => s" (except ${id(c)})")}")
    allChannels.writeAndFlush(message, except.fold(ChannelMatchers.all())(ChannelMatchers.isNot))
  }

  private def blacklist(channel: Channel): Unit = {
    val hostname = channel.asInstanceOf[NioSocketChannel].remoteAddress().getAddress
    log.debug(s"${id(channel)} Blacklisting $hostname")
    peerDatabase.blacklistHost(hostname)
    channel.close()
  }

  def shutdown(): Unit = try {
    serverChannel.close().await()
    log.debug("Unbound server")
    allChannels.close().await()
    log.debug("Closed all channels")
  } finally {
    workerGroup.shutdownGracefully()
    bossGroup.shutdownGracefully()
    localClientGroup.shutdownGracefully()
    localServerGroup.shutdownGracefully()
    coordinatorExecutor.shutdownGracefully()
  }
}
