package li.cil.oc.server.driver.item

import li.cil.oc.api
import li.cil.oc.api.driver.Slot
import li.cil.oc.server.component
import net.minecraft.item.ItemStack

object Keyboard extends Item {
  override def worksWith(stack: ItemStack) = isOneOf(stack, api.Items.get("keyboard"))

  override def createEnvironment(stack: ItemStack, container: component.Container) = new component.Keyboard(container)

  // Only allow programmatic 'installation' of the keyboard as an item.
  override def slot(stack: ItemStack) = Slot.None
}