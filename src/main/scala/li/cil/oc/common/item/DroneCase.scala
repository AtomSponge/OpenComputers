package li.cil.oc.common.item

import net.minecraft.item.ItemStack

class DroneCase(val parent: Delegator, val tier: Int) extends Delegate with ItemTier {
  override val unlocalizedName = super.unlocalizedName + tier

  override protected def tierFromDriver(stack: ItemStack) = tier

  override protected def tooltipName = Option(super.unlocalizedName)
}