package li.cil.oc.common.component

import com.google.common.base.Strings
import cpw.mods.fml.common.eventhandler.SubscribeEvent
import cpw.mods.fml.relauncher.Side
import cpw.mods.fml.relauncher.SideOnly
import li.cil.oc.OpenComputers
import li.cil.oc.Settings
import li.cil.oc.api
import li.cil.oc.api.component.TextBuffer.ColorDepth
import li.cil.oc.api.driver.EnvironmentHost
import li.cil.oc.api.machine.Arguments
import li.cil.oc.api.machine.Callback
import li.cil.oc.api.machine.Context
import li.cil.oc.api.network._
import li.cil.oc.api.prefab
import li.cil.oc.client.renderer.TextBufferRenderCache
import li.cil.oc.client.renderer.font.TextBufferRenderData
import li.cil.oc.client.{ComponentTracker => ClientComponentTracker}
import li.cil.oc.client.{PacketSender => ClientPacketSender}
import li.cil.oc.common._
import li.cil.oc.server.component.Keyboard
import li.cil.oc.server.{ComponentTracker => ServerComponentTracker}
import li.cil.oc.server.{PacketSender => ServerPacketSender}
import li.cil.oc.util
import li.cil.oc.util.BlockPosition
import li.cil.oc.util.PackedColor
import li.cil.oc.util.SideTracker
import net.minecraft.client.Minecraft
import net.minecraft.entity.player.EntityPlayer
import net.minecraft.nbt.NBTTagCompound
import net.minecraftforge.event.world.ChunkEvent
import net.minecraftforge.event.world.WorldEvent

import scala.collection.convert.WrapAsScala._
import scala.collection.mutable

class TextBuffer(val host: EnvironmentHost) extends prefab.ManagedEnvironment with api.component.TextBuffer {
  override val node = api.Network.newNode(this, Visibility.Network).
    withComponent("screen").
    withConnector().
    create()

  private var maxResolution = Settings.screenResolutionsByTier(0)

  private var maxDepth = Settings.screenDepthsByTier(0)

  private var aspectRatio = (1.0, 1.0)

  private var powerConsumptionPerTick = Settings.get.screenCost

  // For client side only.
  private var isRendering = true

  private var isDisplaying = true

  private var hasPower = true

  private var relativeLitArea = -1.0

  private var _pendingCommands: Option[PacketBuilder] = None

  private def pendingCommands = _pendingCommands.getOrElse {
    val pb = new CompressedPacketBuilder(PacketType.TextBufferMulti)
    pb.writeUTF(node.address)
    _pendingCommands = Some(pb)
    pb
  }

  var fullyLitCost = computeFullyLitCost()

  // This computes the energy cost (per tick) to keep the screen running if
  // every single "pixel" is lit. This cost increases with higher tiers as
  // their maximum resolution (pixel density) increases. For a basic screen
  // this is simply the configured cost.
  def computeFullyLitCost() = {
    val (w, h) = Settings.screenResolutionsByTier(0)
    val mw = getMaximumWidth
    val mh = getMaximumHeight
    powerConsumptionPerTick * (mw * mh) / (w * h)
  }

  val proxy =
    if (SideTracker.isClient) new TextBuffer.ClientProxy(this)
    else new TextBuffer.ServerProxy(this)

  val data = new util.TextBuffer(maxResolution, PackedColor.Depth.format(maxDepth))

  // ----------------------------------------------------------------------- //

  override val canUpdate = true

  override def update() {
    super.update()
    if (isDisplaying && host.world.getTotalWorldTime % Settings.get.tickFrequency == 0) {
      if (relativeLitArea < 0) {
        // The relative lit area is the number of pixels that are not blank
        // versus the number of pixels in the *current* resolution. This is
        // scaled to multi-block screens, since we only compute this for the
        // origin.
        val w = getWidth
        val h = getHeight
        relativeLitArea = (data.buffer, data.color).zipped.foldLeft(0) {
          case (acc, (line, colors)) => acc + (line, colors).zipped.foldLeft(0) {
            case (acc2, (char, color)) =>
              val bg = PackedColor.unpackBackground(color, data.format)
              val fg = PackedColor.unpackForeground(color, data.format)
              acc2 + (if (char == ' ') if (bg == 0) 0 else 1
              else if (char == 0x2588) if (fg == 0) 0 else 1
              else if (fg == 0 && bg == 0) 0 else 1)
          }
        } / (w * h).toDouble
      }
      if (node != null) {
        val hadPower = hasPower
        val neededPower = relativeLitArea * fullyLitCost * Settings.get.tickFrequency
        hasPower = node.tryChangeBuffer(-neededPower)
        if (hasPower != hadPower) {
          ServerPacketSender.sendTextBufferPowerChange(node.address, isDisplaying && hasPower, host)
        }
      }
    }

    this.synchronized {
      _pendingCommands.foreach(_.sendToPlayersNearHost(host, Option(Settings.get.maxWirelessRange * Settings.get.maxWirelessRange)))
      _pendingCommands = None
    }
  }

  // ----------------------------------------------------------------------- //

  @Callback(direct = true, doc = """function():boolean -- Returns whether the screen is currently on.""")
  def isOn(computer: Context, args: Arguments): Array[AnyRef] = result(isDisplaying)

  @Callback(doc = """function():boolean -- Turns the screen on. Returns true if it was off.""")
  def turnOn(computer: Context, args: Arguments): Array[AnyRef] = {
    val oldPowerState = isDisplaying
    setPowerState(value = true)
    result(isDisplaying != oldPowerState, isDisplaying)
  }

  @Callback(doc = """function():boolean -- Turns off the screen. Returns true if it was on.""")
  def turnOff(computer: Context, args: Arguments): Array[AnyRef] = {
    val oldPowerState = isDisplaying
    setPowerState(value = false)
    result(isDisplaying != oldPowerState, isDisplaying)
  }

  @Callback(direct = true, doc = """function():number, number -- The aspect ratio of the screen. For multi-block screens this is the number of blocks, horizontal and vertical.""")
  def getAspectRatio(context: Context, args: Arguments): Array[AnyRef] = this.synchronized {
    result(aspectRatio._1, aspectRatio._2)
  }

  @Callback(doc = """function():table -- The list of keyboards attached to the screen.""")
  def getKeyboards(context: Context, args: Arguments): Array[AnyRef] = {
    context.pause(0.25)
    host match {
      case screen: tileentity.Screen =>
        Array(screen.screens.map(_.node).flatMap(_.neighbors.filter(_.host.isInstanceOf[Keyboard]).map(_.address)).toArray)
      case _ =>
        Array(node.neighbors.filter(_.host.isInstanceOf[Keyboard]).map(_.address).toArray)
    }
  }

  // ----------------------------------------------------------------------- //

  override def setEnergyCostPerTick(value: Double) {
    powerConsumptionPerTick = value
    fullyLitCost = computeFullyLitCost()
  }

  override def getEnergyCostPerTick = powerConsumptionPerTick

  override def setPowerState(value: Boolean) {
    if (isDisplaying != value) {
      isDisplaying = value
      if (isDisplaying) {
        val neededPower = fullyLitCost * Settings.get.tickFrequency
        hasPower = node.changeBuffer(-neededPower) == 0
      }
      ServerPacketSender.sendTextBufferPowerChange(node.address, isDisplaying && hasPower, host)
    }
  }

  override def getPowerState = isDisplaying

  override def setMaximumResolution(width: Int, height: Int) {
    if (width < 1) throw new IllegalArgumentException("width must be larger or equal to one")
    if (height < 1) throw new IllegalArgumentException("height must be larger or equal to one")
    maxResolution = (width, height)
    fullyLitCost = computeFullyLitCost()
    proxy.onBufferMaxResolutionChange(width, width)
  }

  override def getMaximumWidth = maxResolution._1

  override def getMaximumHeight = maxResolution._2

  override def setAspectRatio(width: Double, height: Double) = this.synchronized(aspectRatio = (width, height))

  override def getAspectRatio = aspectRatio._1 / aspectRatio._2

  override def setResolution(w: Int, h: Int) = {
    val (mw, mh) = maxResolution
    if (w < 1 || h < 1 || w > mw || h > mw || h * w > mw * mh)
      throw new IllegalArgumentException("unsupported resolution")
    // Always send to clients, their state might be dirty.
    proxy.onBufferResolutionChange(w, h)
    if (data.size = (w, h)) {
      if (node != null) {
        node.sendToReachable("computer.signal", "screen_resized", Int.box(w), Int.box(h))
      }
      true
    }
    else false
  }

  override def getWidth = data.width

  override def getHeight = data.height

  override def setMaximumColorDepth(depth: ColorDepth) = maxDepth = depth

  override def getMaximumColorDepth = maxDepth

  override def setColorDepth(depth: ColorDepth) = {
    if (depth.ordinal > maxDepth.ordinal)
      throw new IllegalArgumentException("unsupported depth")
    // Always send to clients, their state might be dirty.
    proxy.onBufferDepthChange(depth)
    data.format = PackedColor.Depth.format(depth)
  }

  override def getColorDepth = data.format.depth

  override def setPaletteColor(index: Int, color: Int) = data.format match {
    case palette: PackedColor.MutablePaletteFormat =>
      palette(index) = color
      proxy.onBufferPaletteChange(index)
    case _ => throw new Exception("palette not available")
  }

  override def getPaletteColor(index: Int) = data.format match {
    case palette: PackedColor.MutablePaletteFormat => palette(index)
    case _ => throw new Exception("palette not available")
  }

  override def setForegroundColor(color: Int) = setForegroundColor(color, isFromPalette = false)

  override def setForegroundColor(color: Int, isFromPalette: Boolean) {
    val value = PackedColor.Color(color, isFromPalette)
    if (data.foreground != value) {
      data.foreground = value
      proxy.onBufferColorChange()
    }
  }

  override def getForegroundColor = data.foreground.value

  override def isForegroundFromPalette = data.foreground.isPalette

  override def setBackgroundColor(color: Int) = setBackgroundColor(color, isFromPalette = false)

  override def setBackgroundColor(color: Int, isFromPalette: Boolean) {
    val value = PackedColor.Color(color, isFromPalette)
    if (data.background != value) {
      data.background = value
      proxy.onBufferColorChange()
    }
  }

  override def getBackgroundColor = data.background.value

  override def isBackgroundFromPalette = data.background.isPalette

  def copy(col: Int, row: Int, w: Int, h: Int, tx: Int, ty: Int) =
    if (data.copy(col, row, w, h, tx, ty))
      proxy.onBufferCopy(col, row, w, h, tx, ty)

  def fill(col: Int, row: Int, w: Int, h: Int, c: Char) =
    if (data.fill(col, row, w, h, c))
      proxy.onBufferFill(col, row, w, h, c)

  def set(col: Int, row: Int, s: String, vertical: Boolean): Unit =
    if (col < data.width && (col >= 0 || -col < s.length)) {
      // Make sure the string isn't longer than it needs to be, in particular to
      // avoid sending too much data to our clients.
      val (x, y, truncated) =
        if (vertical) {
          if (row < 0) (col, 0, s.substring(-row))
          else (col, row, s.substring(0, math.min(s.length, data.height - row)))
        }
        else {
          if (col < 0) (0, row, s.substring(-col))
          else (col, row, s.substring(0, math.min(s.length, data.width - col)))
        }
      if (data.set(x, y, truncated, vertical))
        proxy.onBufferSet(x, row, truncated, vertical)
    }

  def get(col: Int, row: Int) = data.get(col, row)

  override def getForegroundColor(column: Int, row: Int) =
    if (isForegroundFromPalette(column, row)) {
      PackedColor.extractForeground(color(column, row))
    }
    else {
      PackedColor.unpackForeground(color(column, row), data.format)
    }

  override def isForegroundFromPalette(column: Int, row: Int) =
    data.format.isFromPalette(PackedColor.extractForeground(color(column, row)))

  override def getBackgroundColor(column: Int, row: Int) =
    if (isBackgroundFromPalette(column, row)) {
      PackedColor.extractBackground(color(column, row))
    }
    else {
      PackedColor.unpackBackground(color(column, row), data.format)
    }

  override def isBackgroundFromPalette(column: Int, row: Int) =
    data.format.isFromPalette(PackedColor.extractBackground(color(column, row)))

  override def rawSetText(col: Int, row: Int, text: Array[Array[Char]]): Unit = {
    for (y <- row until ((row + text.length) min data.height)) {
      val line = text(y - row)
      Array.copy(line, 0, data.buffer(y), col, line.length min data.width)
    }
    proxy.onBufferRawSetText(col, row, text)
  }

  override def rawSetBackground(col: Int, row: Int, color: Array[Array[Int]]): Unit = {
    for (y <- row until ((row + color.length) min data.height)) {
      val line = color(y - row)
      for (x <- col until ((col + line.length) min data.width)) {
        val packedBackground = data.color(row)(col) & 0x00FF
        val packedForeground = (data.format.deflate(PackedColor.Color(line(x - col))) << PackedColor.ForegroundShift) & 0xFF00
        data.color(row)(col) = (packedForeground | packedBackground).toShort
      }
    }
    // TODO Better for bandwidth to send packed shorts here. Would need a special case for handling on client, though...
    proxy.onBufferRawSetBackground(col, row, color)
  }

  override def rawSetForeground(col: Int, row: Int, color: Array[Array[Int]]): Unit = {
    for (y <- row until ((row + color.length) min data.height)) {
      val line = color(y - row)
      for (x <- col until ((col + line.length) min data.width)) {
        val packedBackground = data.format.deflate(PackedColor.Color(line(x - col))) & 0x00FF
        val packedForeground = data.color(row)(col) & 0xFF00
        data.color(row)(col) = (packedForeground | packedBackground).toShort
      }
    }
    // TODO Better for bandwidth to send packed shorts here. Would need a special case for handling on client, though...
    proxy.onBufferRawSetForeground(col, row, color)
  }

  private def color(column: Int, row: Int) = {
    if (column < 0 || column >= getWidth || row < 0 || row >= getHeight)
      throw new IndexOutOfBoundsException()
    else data.color(row)(column)
  }

  @SideOnly(Side.CLIENT)
  override def renderText() = relativeLitArea != 0 && proxy.render()

  @SideOnly(Side.CLIENT)
  override def renderWidth = TextBufferRenderCache.renderer.charRenderWidth * data.width

  @SideOnly(Side.CLIENT)
  override def renderHeight = TextBufferRenderCache.renderer.charRenderHeight * data.height

  @SideOnly(Side.CLIENT)
  override def setRenderingEnabled(enabled: Boolean) = isRendering = enabled

  @SideOnly(Side.CLIENT)
  override def isRenderingEnabled = isRendering

  override def keyDown(character: Char, code: Int, player: EntityPlayer) =
    proxy.keyDown(character, code, player)

  override def keyUp(character: Char, code: Int, player: EntityPlayer) =
    proxy.keyUp(character, code, player)

  override def clipboard(value: String, player: EntityPlayer) =
    proxy.clipboard(value, player)

  override def mouseDown(x: Int, y: Int, button: Int, player: EntityPlayer) =
    proxy.mouseDown(x, y, button, player)

  override def mouseDrag(x: Int, y: Int, button: Int, player: EntityPlayer) =
    proxy.mouseDrag(x, y, button, player)

  override def mouseUp(x: Int, y: Int, button: Int, player: EntityPlayer) =
    proxy.mouseUp(x, y, button, player)

  override def mouseScroll(x: Int, y: Int, delta: Int, player: EntityPlayer) =
    proxy.mouseScroll(x, y, delta, player)

  // ----------------------------------------------------------------------- //

  override def onConnect(node: Node) {
    super.onConnect(node)
    if (node == this.node) {
      ServerComponentTracker.add(host.world, node.address, this)
    }
  }

  override def onDisconnect(node: Node) {
    super.onDisconnect(node)
    if (node == this.node) {
      ServerComponentTracker.remove(host.world, this)
    }
  }

  // ----------------------------------------------------------------------- //

  override def load(nbt: NBTTagCompound) {
    super.load(nbt)
    if (SideTracker.isClient) {
      if (!Strings.isNullOrEmpty(proxy.nodeAddress)) return // Only load once.
      proxy.nodeAddress = nbt.getCompoundTag("node").getString("address")
      TextBuffer.registerClientBuffer(this)
    }
    else {
      if (nbt.hasKey("buffer")) {
        data.load(nbt.getCompoundTag("buffer"))
      }
      else if (!Strings.isNullOrEmpty(node.address)) {
        data.load(SaveHandler.loadNBT(nbt, node.address + "_buffer"))
      }
    }

    if (nbt.hasKey(Settings.namespace + "isOn")) {
      isDisplaying = nbt.getBoolean(Settings.namespace + "isOn")
    }
    if (nbt.hasKey(Settings.namespace + "hasPower")) {
      hasPower = nbt.getBoolean(Settings.namespace + "hasPower")
    }
    if (nbt.hasKey(Settings.namespace + "maxWidth") && nbt.hasKey(Settings.namespace + "maxHeight")) {
      val maxWidth = nbt.getInteger(Settings.namespace + "maxWidth")
      val maxHeight = nbt.getInteger(Settings.namespace + "maxHeight")
      maxResolution = (maxWidth, maxHeight)
    }
  }

  // Null check for Waila (and other mods that may call this client side).
  override def save(nbt: NBTTagCompound) = if (node != null) {
    super.save(nbt)
    // Happy thread synchronization hack! Here's the problem: GPUs allow direct
    // calls for modifying screens to give a more responsive experience. This
    // causes the following problem: when saving, if the screen is saved first,
    // then the executor runs in parallel and changes the screen *before* the
    // server thread begins saving that computer, the saved computer will think
    // it changed the screen, although the saved screen wasn't. To avoid that we
    // wait for all computers the screen is connected to to finish their current
    // execution and pausing them (which will make them resume in the next tick
    // when their update() runs).
    if (node.network != null) {
      for (node <- node.network.nodes) node.host match {
        case computer: tileentity.traits.Computer if !computer.machine.isPaused =>
          computer.machine.pause(0.1)
        case _ =>
      }
    }

    SaveHandler.scheduleSave(host, nbt, node.address + "_buffer", data.save _)
    nbt.setBoolean(Settings.namespace + "isOn", isDisplaying)
    nbt.setBoolean(Settings.namespace + "hasPower", hasPower)
    nbt.setInteger(Settings.namespace + "maxWidth", maxResolution._1)
    nbt.setInteger(Settings.namespace + "maxHeight", maxResolution._2)
  }
}

object TextBuffer {
  var clientBuffers = mutable.ListBuffer.empty[TextBuffer]

  @SubscribeEvent
  def onChunkUnload(e: ChunkEvent.Unload) {
    val chunk = e.getChunk
    clientBuffers = clientBuffers.filter(t => {
      val blockPos = BlockPosition(t.host)
      val keep = t.host.world != e.world || !chunk.isAtLocation(blockPos.x >> 4, blockPos.z >> 4)
      if (!keep) {
        ClientComponentTracker.remove(t.host.world, t)
      }
      keep
    })
  }

  @SubscribeEvent
  def onWorldUnload(e: WorldEvent.Unload) {
    clientBuffers = clientBuffers.filter(t => {
      val keep = t.host.world != e.world
      if (!keep) {
        ClientComponentTracker.remove(t.host.world, t)
      }
      keep
    })
  }

  def registerClientBuffer(t: TextBuffer) {
    ClientPacketSender.sendTextBufferInit(t.proxy.nodeAddress)
    ClientComponentTracker.add(t.host.world, t.proxy.nodeAddress, t)
    clientBuffers += t
  }

  abstract class Proxy {
    def owner: TextBuffer

    var dirty = false

    var nodeAddress = ""

    def markDirty() {
      dirty = true
    }

    def render() = false

    def onBufferColorChange(): Unit

    def onBufferCopy(col: Int, row: Int, w: Int, h: Int, tx: Int, ty: Int) {
      owner.relativeLitArea = -1
    }

    def onBufferDepthChange(depth: ColorDepth): Unit

    def onBufferFill(col: Int, row: Int, w: Int, h: Int, c: Char) {
      owner.relativeLitArea = -1
    }

    def onBufferPaletteChange(index: Int): Unit

    def onBufferResolutionChange(w: Int, h: Int) {
      owner.relativeLitArea = -1
    }

    def onBufferMaxResolutionChange(w: Int, h: Int) {
    }

    def onBufferSet(col: Int, row: Int, s: String, vertical: Boolean) {
      owner.relativeLitArea = -1
    }

    def onBufferRawSetText(col: Int, row: Int, text: Array[Array[Char]]) {
      owner.relativeLitArea = -1
    }

    def onBufferRawSetBackground(col: Int, row: Int, color: Array[Array[Int]]) {
      owner.relativeLitArea = -1
    }

    def onBufferRawSetForeground(col: Int, row: Int, color: Array[Array[Int]]) {
      owner.relativeLitArea = -1
    }

    def keyDown(character: Char, code: Int, player: EntityPlayer): Unit

    def keyUp(character: Char, code: Int, player: EntityPlayer): Unit

    def clipboard(value: String, player: EntityPlayer): Unit

    def mouseDown(x: Int, y: Int, button: Int, player: EntityPlayer): Unit

    def mouseDrag(x: Int, y: Int, button: Int, player: EntityPlayer): Unit

    def mouseUp(x: Int, y: Int, button: Int, player: EntityPlayer): Unit

    def mouseScroll(x: Int, y: Int, delta: Int, player: EntityPlayer): Unit
  }

  class ClientProxy(val owner: TextBuffer) extends Proxy {
    val renderer = new TextBufferRenderData {
      override def dirty = ClientProxy.this.dirty

      override def dirty_=(value: Boolean) = ClientProxy.this.dirty = value

      override def data = owner.data
    }

    override def render() = {
      val wasDirty = dirty
      TextBufferRenderCache.render(renderer)
      wasDirty
    }

    override def onBufferColorChange() {
      markDirty()
    }

    override def onBufferCopy(col: Int, row: Int, w: Int, h: Int, tx: Int, ty: Int) {
      super.onBufferCopy(col, row, w, h, tx, ty)
      markDirty()
    }

    override def onBufferDepthChange(depth: ColorDepth) {
      markDirty()
    }

    override def onBufferFill(col: Int, row: Int, w: Int, h: Int, c: Char) {
      super.onBufferFill(col, row, w, h, c)
      markDirty()
    }

    override def onBufferPaletteChange(index: Int) {
      markDirty()
    }

    override def onBufferResolutionChange(w: Int, h: Int) {
      super.onBufferResolutionChange(w, h)
      markDirty()
    }

    override def onBufferSet(col: Int, row: Int, s: String, vertical: Boolean) {
      super.onBufferSet(col, row, s, vertical)
      dirty = true
    }

    override def keyDown(character: Char, code: Int, player: EntityPlayer) {
      debug(s"{type = keyDown, char = $character, code = $code}")
      ClientPacketSender.sendKeyDown(nodeAddress, character, code)
    }

    override def keyUp(character: Char, code: Int, player: EntityPlayer) {
      debug(s"{type = keyUp, char = $character, code = $code}")
      ClientPacketSender.sendKeyUp(nodeAddress, character, code)
    }

    override def clipboard(value: String, player: EntityPlayer) {
      debug(s"{type = clipboard}")
      ClientPacketSender.sendClipboard(nodeAddress, value)
    }

    override def mouseDown(x: Int, y: Int, button: Int, player: EntityPlayer) {
      debug(s"{type = mouseDown, x = $x, y = $y, button = $button}")
      ClientPacketSender.sendMouseClick(nodeAddress, x, y, drag = false, button)
    }

    override def mouseDrag(x: Int, y: Int, button: Int, player: EntityPlayer) {
      debug(s"{type = mouseDrag, x = $x, y = $y, button = $button}")
      ClientPacketSender.sendMouseClick(nodeAddress, x, y, drag = true, button)
    }

    override def mouseUp(x: Int, y: Int, button: Int, player: EntityPlayer) {
      debug(s"{type = mouseUp, x = $x, y = $y, button = $button}")
      ClientPacketSender.sendMouseUp(nodeAddress, x, y, button)
    }

    override def mouseScroll(x: Int, y: Int, delta: Int, player: EntityPlayer) {
      debug(s"{type = mouseScroll, x = $x, y = $y, delta = $delta}")
      ClientPacketSender.sendMouseScroll(nodeAddress, x, y, delta)
    }

    private lazy val Debugger = api.Items.get("debugger")

    private def debug(message: String) {
      if (Minecraft.getMinecraft != null && Minecraft.getMinecraft.thePlayer != null && api.Items.get(Minecraft.getMinecraft.thePlayer.getHeldItem) == Debugger) {
        OpenComputers.log.info(s"[NETWORK DEBUGGER] Sending packet to node $nodeAddress: " + message)
      }
    }
  }

  class ServerProxy(val owner: TextBuffer) extends Proxy {
    override def onBufferColorChange() {
      owner.host.markChanged()
      owner.synchronized(ServerPacketSender.appendTextBufferColorChange(owner.pendingCommands, owner.data.foreground, owner.data.background))
    }

    override def onBufferCopy(col: Int, row: Int, w: Int, h: Int, tx: Int, ty: Int) {
      super.onBufferCopy(col, row, w, h, tx, ty)
      owner.host.markChanged()
      owner.synchronized(ServerPacketSender.appendTextBufferCopy(owner.pendingCommands, col, row, w, h, tx, ty))
    }

    override def onBufferDepthChange(depth: ColorDepth) {
      owner.host.markChanged()
      owner.synchronized(ServerPacketSender.appendTextBufferDepthChange(owner.pendingCommands, depth))
    }

    override def onBufferFill(col: Int, row: Int, w: Int, h: Int, c: Char) {
      super.onBufferFill(col, row, w, h, c)
      owner.host.markChanged()
      owner.synchronized(ServerPacketSender.appendTextBufferFill(owner.pendingCommands, col, row, w, h, c))
    }

    override def onBufferPaletteChange(index: Int) {
      owner.host.markChanged()
      owner.synchronized(ServerPacketSender.appendTextBufferPaletteChange(owner.pendingCommands, index, owner.getPaletteColor(index)))
    }

    override def onBufferResolutionChange(w: Int, h: Int) {
      super.onBufferResolutionChange(w, h)
      owner.host.markChanged()
      owner.synchronized(ServerPacketSender.appendTextBufferResolutionChange(owner.pendingCommands, w, h))
    }

    override def onBufferMaxResolutionChange(w: Int, h: Int) {
      if (owner.node.network != null) {
        super.onBufferMaxResolutionChange(w, h)
        owner.host.markChanged()
        owner.synchronized(ServerPacketSender.appendTextBufferMaxResolutionChange(owner.pendingCommands, w, h))
      }
    }

    override def onBufferSet(col: Int, row: Int, s: String, vertical: Boolean) {
      super.onBufferSet(col, row, s, vertical)
      owner.host.markChanged()
      owner.synchronized(ServerPacketSender.appendTextBufferSet(owner.pendingCommands, col, row, s, vertical))
    }

    override def onBufferRawSetText(col: Int, row: Int, text: Array[Array[Char]]) {
      super.onBufferRawSetText(col, row, text)
      owner.host.markChanged()
      owner.synchronized(ServerPacketSender.appendTextBufferRawSetText(owner.pendingCommands, col, row, text))
    }

    override def onBufferRawSetBackground(col: Int, row: Int, color: Array[Array[Int]]) {
      super.onBufferRawSetBackground(col, row, color)
      owner.host.markChanged()
      owner.synchronized(ServerPacketSender.appendTextBufferRawSetBackground(owner.pendingCommands, col, row, color))
    }

    override def onBufferRawSetForeground(col: Int, row: Int, color: Array[Array[Int]]) {
      super.onBufferRawSetForeground(col, row, color)
      owner.host.markChanged()
      owner.synchronized(ServerPacketSender.appendTextBufferRawSetForeground(owner.pendingCommands, col, row, color))
    }

    override def keyDown(character: Char, code: Int, player: EntityPlayer) {
      sendToKeyboards("keyboard.keyDown", player, Char.box(character), Int.box(code))
    }

    override def keyUp(character: Char, code: Int, player: EntityPlayer) {
      sendToKeyboards("keyboard.keyUp", player, Char.box(character), Int.box(code))
    }

    override def clipboard(value: String, player: EntityPlayer) {
      sendToKeyboards("keyboard.clipboard", player, value)
    }

    override def mouseDown(x: Int, y: Int, button: Int, player: EntityPlayer) {
      if (Settings.get.inputUsername) owner.node.sendToReachable("computer.checked_signal", player, "touch", Int.box(x), Int.box(y), Int.box(button), player.getCommandSenderName)
      else owner.node.sendToReachable("computer.checked_signal", player, "touch", Int.box(x), Int.box(y), Int.box(button))
    }

    override def mouseDrag(x: Int, y: Int, button: Int, player: EntityPlayer) {
      if (Settings.get.inputUsername) owner.node.sendToReachable("computer.checked_signal", player, "drag", Int.box(x), Int.box(y), Int.box(button), player.getCommandSenderName)
      else owner.node.sendToReachable("computer.checked_signal", player, "drag", Int.box(x), Int.box(y), Int.box(button))
    }

    override def mouseUp(x: Int, y: Int, button: Int, player: EntityPlayer) {
      if (Settings.get.inputUsername) owner.node.sendToReachable("computer.checked_signal", player, "drop", Int.box(x), Int.box(y), Int.box(button), player.getCommandSenderName)
      else owner.node.sendToReachable("computer.checked_signal", player, "drop", Int.box(x), Int.box(y), Int.box(button))
    }

    override def mouseScroll(x: Int, y: Int, delta: Int, player: EntityPlayer) {
      if (Settings.get.inputUsername) owner.node.sendToReachable("computer.checked_signal", player, "scroll", Int.box(x), Int.box(y), Int.box(delta), player.getCommandSenderName)
      else owner.node.sendToReachable("computer.checked_signal", player, "scroll", Int.box(x), Int.box(y), Int.box(delta))
    }

    private def sendToKeyboards(name: String, values: AnyRef*) {
      owner.host match {
        case screen: tileentity.Screen =>
          screen.screens.foreach(_.node.sendToNeighbors(name, values: _*))
        case _ =>
          owner.node.sendToNeighbors(name, values: _*)
      }
    }
  }

}