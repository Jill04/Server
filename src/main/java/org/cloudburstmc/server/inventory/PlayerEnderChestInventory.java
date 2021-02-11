package org.cloudburstmc.server.inventory;

import com.nukkitx.protocol.bedrock.data.SoundEvent;
import com.nukkitx.protocol.bedrock.data.inventory.ContainerType;
import com.nukkitx.protocol.bedrock.packet.ContainerClosePacket;
import com.nukkitx.protocol.bedrock.packet.ContainerOpenPacket;
import org.cloudburstmc.api.blockentity.EnderChest;
import org.cloudburstmc.api.inventory.InventoryType;
import org.cloudburstmc.math.vector.Vector3i;
import org.cloudburstmc.server.entity.EntityHuman;
import org.cloudburstmc.server.level.CloudLevel;
import org.cloudburstmc.server.player.CloudPlayer;

public class PlayerEnderChestInventory extends BaseInventory {

    public PlayerEnderChestInventory(EntityHuman player) {
        super(player, InventoryType.ENDER_CHEST);
    }

    @Override
    public EntityHuman getHolder() {
        return (EntityHuman) this.holder;
    }

    @Override
    public void onOpen(CloudPlayer who) {
        if (who != this.getHolder()) {
            return;
        }
        super.onOpen(who);
        ContainerOpenPacket containerOpenPacket = new ContainerOpenPacket();
        containerOpenPacket.setId(who.getWindowId(this));
        containerOpenPacket.setType(ContainerType.from(this.getType().getNetworkType()));
        EnderChest chest = who.getViewingEnderChest();
        if (chest != null) {
            containerOpenPacket.setBlockPosition(chest.getPosition());
        } else {
            containerOpenPacket.setBlockPosition(Vector3i.ZERO);
        }

        who.sendPacket(containerOpenPacket);

        this.sendContents(who);

        if (chest != null && chest.getInventory().getViewers().size() == 1) {
            CloudLevel level = this.getHolder().getLevel();
            if (level != null) {
                level.addLevelSoundEvent(this.getHolder().getPosition().add(0.5, 0.5, 0.5), SoundEvent.ENDERCHEST_OPEN);
                CloudContainer.sendBlockEventPacket(level.getBlockEntity(chest.getPosition()), 1);
            }
        }
    }

    @Override
    public void onClose(CloudPlayer who) {
        ContainerClosePacket containerClosePacket = new ContainerClosePacket();
        containerClosePacket.setId(who.getWindowId(this));
        who.sendPacket(containerClosePacket);
        super.onClose(who);

        EnderChest chest = who.getViewingEnderChest();
        if (chest != null && chest.getInventory().getViewers().size() == 1) {
            CloudLevel level = this.getHolder().getLevel();
            if (level != null) {
                level.addLevelSoundEvent(this.getHolder().getPosition().add(0.5, 0.5, 0.5), SoundEvent.ENDERCHEST_CLOSED);
                CloudContainer.sendBlockEventPacket(level.getBlockEntity(chest.getPosition()), 0);
            }

            who.setViewingEnderChest(null);
        }

        super.onClose(who);
    }
}
