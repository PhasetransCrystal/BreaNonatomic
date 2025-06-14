package com.phasetranscrystal.nonatomic.core;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.NotNull;
import org.lwjgl.system.NonnullDefault;

import java.util.*;

public class OperatorEntity extends Mob {
    public static final Logger LOGGER = LogManager.getLogger("BreaNona:OperatorEntity");

    public UUID getBelongingUUID() {
        return belonging;
    }

    public Operator.Identifier getIdentifier() {
        return identifier;
    }

    public Operator getOperator() {
        return operator;
    }

    public void setOperator(Operator operator) {
        this.operator = operator;
    }

    public ResourceLocation getContainerID() {
        return containerID;
    }

    private @NonnullDefault ResourceLocation containerID;
    private @NonnullDefault UUID belonging;
    private @NonnullDefault Operator.Identifier identifier;
    private @NonnullDefault Operator operator;

    public OperatorEntity(EntityType<? extends OperatorEntity> entityType, Level level) {
        super(entityType, level);
    }

    //用这个创建您的新干员
    @SuppressWarnings("all")
    public OperatorEntity(EntityType<? extends OperatorEntity> entityType, Operator operatorData, ServerPlayer belonging) {
        this(entityType, (ServerLevel) belonging.level(), belonging, operatorData, operatorData.getOpeHandler().containerId());
    }

    public OperatorEntity(EntityType<? extends OperatorEntity> entityType, ServerLevel level, Player belonging, Operator operatorData, ResourceLocation containerID) {
        super(entityType, level);
        this.containerID = containerID;
        this.belonging = belonging.getUUID();
        this.operator = operatorData;
        this.identifier = operatorData.identifier;
    }

    @Override
    public void load(CompoundTag compoundTag) {
        super.load(compoundTag);
        containerID = compoundTag.contains("opeContainerId") ? ResourceLocation.tryParse(compoundTag.getString("opeContainerId")) : null;
        belonging = compoundTag.contains("opeBelonging") ? compoundTag.getUUID("opeBelonging") : null;
        identifier = Operator.Identifier.CODEC.parse(NbtOps.INSTANCE, compoundTag.get("opeIdentifier")).mapOrElse(i -> i, e -> null);
    }

    @Override
    public CompoundTag saveWithoutId(CompoundTag compoundTag) {
        compoundTag.putString("opeContainerId", containerID.toString());
        compoundTag.putUUID("opeBelonging", belonging);
        compoundTag.put("opeIdentifier", Operator.Identifier.CODEC.encode(identifier, NbtOps.INSTANCE, new CompoundTag()).getOrThrow());

        return super.saveWithoutId(compoundTag);
    }

    public void opeInit() {
    }

    /**
     * 可以覆写此方法，此方法在允许数据合并时被调用。
     */
    protected Collection<OperatorInfo<?>> getExternalOpeInfo() {
        return Collections.emptyList();
    }

    protected ListTag saveExternalOpeInfo() {
        Collection<? extends OperatorInfo<?>> unmergedInfo = getExternalOpeInfo();
        if (!unmergedInfo.isEmpty()) {
            ListTag unmerged = new ListTag();
            unmergedInfo.forEach(info -> unmerged.add(OperatorInfo.CODEC.encode(info, NbtOps.INSTANCE, new CompoundTag()).getOrThrow()));
            return unmerged;
        }
        return null;
    }

    protected Collection<? extends OperatorInfo<?>> loadExternalOpeInfo(ListTag listTag) {
        List<OperatorInfo<?>> list = new ArrayList<>();
        listTag.forEach(tag -> {
            try {
                OperatorInfo<?> info = OperatorInfo.CODEC.parse(NbtOps.INSTANCE, tag).getOrThrow();
                list.add(info);
            } catch (IllegalStateException exception) {
                LOGGER.warn("Unable to parse operator info for entity(ope = {}). Skipped.", identifier);
                LOGGER.debug("Details: opeEntity = {}, nbt = {}", identifierInfoString(), tag);
                LOGGER.debug("Stacktrace:", exception);
            }
        });
        return list;
    }

    //实现该方法以正确处理合并时的合并成功或删除要求。
    //不要在不清楚的情况下调用这个
    protected void onExternalOpeInfoRemove(OperatorInfo<?> info, boolean merged) {
    }

    @Override
    public boolean removeWhenFarAway(double d) {
        return false;
    }

    public ServerPlayer getOwner() {
        return getOperator().getOpeHandler().owner();
    }

    //可以在这里写一点撤退动画
    public void onRetreat() {
        this.remove(RemovalReason.UNLOADED_WITH_PLAYER);
    }

    public void teleportToPlayer() {
        BlockPos toPos = identifier.type().findPlaceForGenerate(getOwner(), null);
        if (toPos == null && getOwner() != null)
            toPos = new BlockPos(getOwner().getBlockX(), getOwner().getBlockY(), getOwner().getBlockZ());
        if (toPos == null) this.operator.retreat(true, Operator.RetreatReason.TELEPORT_FAILED);
        else
            teleportTo((ServerLevel) getOwner().level(), toPos.getX() + 0.5, toPos.getY(), toPos.getZ() + 0.5, Set.of(), getYRot(), getXRot());
    }

    @Override
    public @NotNull String toString() {
        return identifierInfoString();
    }

    public String identifierInfoString() {
        return "OperatorEntity{" +
                "identifier=" + identifier +
                ", belonging=" + belonging +
                ", containerID=" + containerID +
                '}';
    }
}
