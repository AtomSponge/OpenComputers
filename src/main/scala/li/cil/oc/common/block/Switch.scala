package li.cil.oc.common.block

import li.cil.oc.OpenComputers
import li.cil.oc.Settings
import li.cil.oc.client.Textures
import li.cil.oc.common.GuiType
import li.cil.oc.common.tileentity
import net.minecraft.client.renderer.texture.IIconRegister
import net.minecraft.entity.player.EntityPlayer
import net.minecraft.world.World
import net.minecraftforge.common.util.ForgeDirection

class Switch extends SimpleBlock {
  override protected def customTextures = Array(
    None,
    Some("SwitchTop"),
    Some("SwitchSide"),
    Some("SwitchSide"),
    Some("SwitchSide"),
    Some("SwitchSide")
  )

  override def registerBlockIcons(iconRegister: IIconRegister) = {
    super.registerBlockIcons(iconRegister)
    Textures.Switch.iconSideActivity = iconRegister.registerIcon(Settings.resourceDomain + ":SwitchSideOn")
  }

  // ----------------------------------------------------------------------- //

  override def hasTileEntity(metadata: Int) = true

  override def createTileEntity(world: World, metadata: Int) = new tileentity.Switch()

  // ----------------------------------------------------------------------- //

  override def onBlockActivated(world: World, x: Int, y: Int, z: Int, player: EntityPlayer,
                                side: ForgeDirection, hitX: Float, hitY: Float, hitZ: Float) = {
    world.getTileEntity(x, y, z) match {
      case switch: tileentity.Switch =>
        if (!player.isSneaking) {
          if (!world.isRemote) {
            player.openGui(OpenComputers, GuiType.Switch.id, world, x, y, z)
          }
          true
        }
        else false
    }
  }
}
