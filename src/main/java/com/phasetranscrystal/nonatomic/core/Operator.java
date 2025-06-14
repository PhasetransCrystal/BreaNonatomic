package com.phasetranscrystal.nonatomic.core;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.phasetranscrystal.nonatomic.EventHooks;
import com.phasetranscrystal.nonatomic.Nonatomic;
import com.phasetranscrystal.nonatomic.Registries;
import com.phasetranscrystal.nonatomic.core.info.IAttributesProvider;
import com.phasetranscrystal.nonatomic.misc.LevelAndPosRecorder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.system.NonnullDefault;

import java.util.*;
import java.util.function.Supplier;

/**
 * <p>干员</p>
 * <p>在本库中，干员是玩家拥有的完整持久化单位的最小单位，在原则上不需要继承并创建子类。
 * 干员负责存储一系列数据，包括标定本干员的特征信息等。</p>
 * <p>在干员部署时，默认跟随状态下会存储干员实体，其它状态下只会标定干员的最后位置与实体uuid。
 * 所有数据原则上不存储在所属玩家身上——而是应该存储在维度中，以便于实体创建时的同步和防止玩家数据量过大。</p>
 * <p>在生物实体被创建时，会尝试与其对应的干员实例建立联系，验证合法性，引用数据与同步最后位置。</p>
 */
public class Operator {
    //芝士Codec
    public static final Codec<Operator> CODEC = RecordCodecBuilder.create(n -> n.group(
            Identifier.CODEC.fieldOf("identifier").forGetter(i -> i.identifier),
            OperatorInfo.CODEC.listOf().fieldOf("infos").forGetter(i -> i.infos.values().stream().toList()),
            EntityFinderInfo.CODEC.optionalFieldOf("finder").forGetter(i -> i.entityFinderInfo),
            Codec.BOOL.fieldOf("redeploy").forGetter(i -> i.redeployFlag),
            ResourceLocation.CODEC.fieldOf("status").forGetter(i -> i.status)
    ).apply(n, Operator::new));

    //预配的状态 默认只有STATUS_WORKING会保存实体
    public static final ResourceLocation STATUS_READY = ResourceLocation.fromNamespaceAndPath(Nonatomic.MOD_ID, "ready");//空闲状态
    public static final ResourceLocation STATUS_REST = ResourceLocation.fromNamespaceAndPath(Nonatomic.MOD_ID, "rest");//休息状态
    public static final ResourceLocation STATUS_TRACKING = ResourceLocation.fromNamespaceAndPath(Nonatomic.MOD_ID, "tracking");//跟随状态
    public static final ResourceLocation STATUS_ALERT = ResourceLocation.fromNamespaceAndPath(Nonatomic.MOD_ID, "alert");//警戒状态
    public static final ResourceLocation STATUS_WORKING = ResourceLocation.fromNamespaceAndPath(Nonatomic.MOD_ID, "working");//工作状态
    public static final ResourceLocation STATUS_DISPATCHING = ResourceLocation.fromNamespaceAndPath(Nonatomic.MOD_ID, "dispatching");//外派状态

    // ---[从这里开始]---

    /**
     * - {@link Operator#identifier}是干员实例特征的唯一标识符，必须包含干员种类以及一个选含的UUID，用于便于{@link OpeHandler 干员管理器}管理与记录。<p>
     * - {@link Operator#infos}存储该干员附加的所有信息——全部以类似组件的形式存在。在干员实体中，只要通过了合法性验证，就可以直接访问干员从而访问数据。<p>
     * - {@link Operator#opeHandler}就是我们上面提到过的干员管理器。干员所属的玩家与总部署状态表都可以通过该实例获取。在init方法中被加载。<p>
     * - {@link Operator#entity}在需要记录实体的时候，这个变量将被对应的实体填充。在默认情况下，只有跟随状态干员会记录。
     * 请不要随意记录其它状态的干员——除非您明白您在干什么——这可能导致干员在被世界移除后一直被持续引用。<p>
     * - {@link Operator#entityFinderInfo}记录实体的uuid与最后位置，用于帮助玩家找到实体以及验证实体合法性。
     */
    public final Identifier identifier;
    protected final HashMap<MapCodec<? extends OperatorInfo<?>>, OperatorInfo<?>> infos = new HashMap<>();

    @NonnullDefault
    private OpeHandler opeHandler;

    @Nullable
    private OperatorEntity entity;
    private @SuppressWarnings("all") Optional<EntityFinderInfo> entityFinderInfo = Optional.empty();
    private boolean redeployFlag = false;

    private ResourceLocation status = STATUS_READY;

    public void setEntityNull() {
        this.entity = null;
    }

    // ---[构造函数]---

    public Operator(OperatorType operatorType) {
        this(new Identifier(operatorType));
    }

    public Operator(Identifier identifier) {
        this.identifier = identifier;
    }

    @SuppressWarnings("all")
    public Operator(Identifier identifier, List<? extends OperatorInfo> infos, Optional<EntityFinderInfo> entityFinderInfo, boolean redeployFlag, ResourceLocation status) {
        this.identifier = identifier;
        this.entityFinderInfo = entityFinderInfo;
        this.redeployFlag = redeployFlag;
        this.status = status;
        for (OperatorInfo info : infos) {
            this.infos.put(info.codec(), info);
        }
    }


    public void init(OpeHandler handler) {
        this.opeHandler = handler;
        infos.values().forEach(info -> info.init(this));
    }

    public void login(ServerPlayer player) {
        if (redeployFlag) {
            redeployFlag = false;
            int result = redeploy();
            if (result != 0) EventHooks.deployFailed(this, opeHandler.owner(), result);
        }

        infos.values().forEach(OperatorInfo::login);
    }

    public void logout() {
        boolean flag = EventHooks.onPlayerLogoutOpe(opeHandler.owner(), this, entity);

        if (this.status.equals(STATUS_TRACKING)) {
            disconnectWithEntity(Entity.RemovalReason.UNLOADED_WITH_PLAYER, STATUS_TRACKING, RetreatReason.UNLOADED_WITH_PLAYER);
            this.redeployFlag = flag;
        }
        infos.values().forEach(OperatorInfo::logout);
    }


    // ---[实体处理部分]---

    //检查实体合法性
    @SuppressWarnings("all")
    public boolean checkEntityLegality(OperatorEntity entity, boolean newCreated) {
        return entity != null && entity.getBelongingUUID().equals(opeHandler.ownerUUId()) &&
                entity.getIdentifier().equals(this.identifier) &&
                (newCreated || (this.entityFinderInfo.isPresent() && this.entityFinderInfo.get().match(entity)));
    }

    //生物实体的数据同步 对于干员实体，也使用这个给自己同步即可
    //记得创建生物实体前先给operator设status
    //原则上不缓存attribute变更
    public boolean entityCreated(OperatorEntity entity, boolean isNew) {
        if (!checkEntityLegality(entity, isNew)) return false;

        entity.setOperator(this);
        this.entity = entity; //在实体卸载时被清除
        infos.values().forEach(
                isNew ? info -> {
                    info.entityCreated(entity);
                    if (info instanceof IAttributesProvider p) p.getAttributes().forEach(re -> re.attach(entity));
                } : info -> info.entityInit(entity));

        markLastPos(entity);

        return true;
    }


    //合并实体信息
    //不安全 请使用下面的合集版本 但是方便
    public void mergeDataFromEntity(RetreatReason reason, MapCodec<? extends OperatorInfo<?>>... types) {
        mergeDataFromEntity(reason, Set.of(types));
    }

    public void mergeDataFromEntity(RetreatReason reason, Collection<MapCodec<? extends OperatorInfo<?>>> types) {
        if (entity == null) return;
        entity.getExternalOpeInfo().stream().filter(i -> types.isEmpty() || types.contains(i.codec())).forEach(info -> {
            switch (EventHooks.allowDataMerge(reason, entity, this, info)) {
                case 1 -> dataMerge(info);
                case -1 -> entity.onExternalOpeInfoRemove(info, false);
            }
        });
    }

    @SuppressWarnings("all")
    private <T extends OperatorInfo<T>> void dataMerge(OperatorInfo<T> info){
        MapCodec<T> codec = info.codec();
        if (infos.containsKey(codec)) {
            getInfo(info.codec()).merge((T) info);
        } else {
            infos.put(codec, info);
        }
        entity.onExternalOpeInfoRemove(info, true);
    }

    public void disconnectWithEntity() {
        disconnectWithEntity(Entity.RemovalReason.DISCARDED, STATUS_REST, RetreatReason.FOCUS);
    }

    //清除实体信息或格式化状态信息
    public void disconnectWithEntity(Entity.RemovalReason reason, ResourceLocation status, RetreatReason retreatReason) {
        if (entityFinderInfo.isPresent()) {
            identifier.type.onRetreat(opeHandler.ownerOrUUID(), this);
            EventHooks.onRetreat(this, retreatReason);
        }
        if (entity != null && reason != null) {
            mergeDataFromEntity(retreatReason);
            if (reason == Entity.RemovalReason.UNLOADED_WITH_PLAYER) entity.onRetreat();
            else if (reason != Entity.RemovalReason.KILLED) entity.remove(reason);
            setEntityNull();
        }
        entityFinderInfo = Optional.empty();
        redeployFlag = false;
        this.status = status;
    }

    // ---[部署与撤退]---

    private int redeploy() {
        ServerPlayer player = opeHandler.owner();
        if (!identifier.type.allowDeploy(player, this)) return -4;

        BlockPos pos = identifier.type.findPlaceForGenerate(player, null);
        if (pos == null) return -6;

        finalGenEntity(player, pos, true);

        return 0;
    }

    public int deploy(boolean focus, boolean markWhenNoPlayer, @Nullable BlockPos expectPos) {
        return deploy(focus, markWhenNoPlayer, -1, expectPos, STATUS_TRACKING);
    }


    /**
     * 尝试部署一个干员
     *
     * @param focus            是否强制部署
     * @param markWhenNoPlayer 是否在玩家不存在时标记部署
     * @param expectPos        预期的部署位置
     * @param expectStatus
     * @return <p> 返回的状态码 <p> >0 -> 完成部署的位置索引 INT_MAX -> 标记部署</p>
     * @see com.phasetranscrystal.nonatomic.event.OperatorEvent.DeployFailed OperatorEvent.DeployFailed Event
     */
    @SuppressWarnings("all")
    public int deploy(boolean focus, boolean markWhenNoPlayer, int expectIndex, @Nullable BlockPos expectPos, ResourceLocation expectStatus) {
        int result = deployWrapped(focus, markWhenNoPlayer, expectIndex, expectPos, expectStatus);
        if (result < 0) {
            EventHooks.deployFailed(this, opeHandler.owner(), result);
        }
        return result;
    }

    private int deployWrapped(boolean focus, boolean markWhenNoPlayer, int expectIndex, @Nullable BlockPos expectPos, ResourceLocation expectStatus) {

        if (entity != null && !focus) return -2;
        if (!status.equals(STATUS_READY) && !focus) return -3;

        ServerPlayer player = opeHandler.owner();
        if (player == null) {
            this.redeployFlag = redeployFlag || markWhenNoPlayer;
            return markWhenNoPlayer ? -256 : -1;
        }

        if (!identifier.type.allowDeploy(player, this)) return -4;

        int indexFlag = -1;

        if (focus) {
            if (entityFinderInfo.isPresent()) indexFlag = retreat(true, RetreatReason.FOCUS);
            else indexFlag = opeHandler.onRetreat(this);
        }

        boolean deployFlag = EventHooks.ifTakeDeployPlace(this, expectStatus);
        if (deployFlag) {
            if (indexFlag < 0) {
                indexFlag = opeHandler.addDeploying(this, expectIndex, true, true);
            }
            if (indexFlag < 0) return -5;
        }

        BlockPos pos = identifier.type.findPlaceForGenerate(player, expectPos);
        if (pos == null) return -6;

        this.status = expectStatus;

        finalGenEntity(player, pos, false);

        return deployFlag ? opeHandler.addDeploying(this, indexFlag, false, true) : -1;
    }

    private void finalGenEntity(ServerPlayer player, BlockPos pos, boolean isRedeploy) {
        OperatorEntity created = this.getType().createEntityInstance(this, player);
        created.setPos(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5);
        this.getType().onDeploy(created, player, this);
        player.serverLevel().addFreshEntity(created);
    }


    /**
     * 使该干员撤退 不包含自然撤退情况 不要在客户端调用这个
     *
     * @param focus 是否强制撤退
     * @return <0 -> 不被允许的撤退或不支持的部署位置索引  否则为对应的部署位置索引
     */
    @SuppressWarnings("deprecation")
    public int retreat(boolean focus, RetreatReason reason) {

        if (focus) {
            disconnectWithEntity();
            return opeHandler.onRetreat(this);
        }

        if (opeHandler.owner() == null) return -1;

        Optional<ResourceLocation> state = identifier.type.allowRetreat(entity, opeHandler.ownerOrUUID(), this, reason) ? EventHooks.preRetreat(opeHandler.owner(), this, reason) : Optional.empty();
        if (state.isPresent()) {
            disconnectWithEntity(Entity.RemovalReason.UNLOADED_WITH_PLAYER, state.get(), reason);
            return opeHandler.onRetreat(this);
        }
        return -1;
    }

    public enum RetreatReason {
        UNLOADED_WITH_PLAYER,
        RETREAT,
        KILLED,
        PLAYER_FAILED,
        TELEPORT_FAILED,
        MISSION_ACCOMPLISHED,
        MISSION_FAILED,
        FOCUS,
        TRUE,
        FALSE,
        MISC
    }

    //当干员死亡 不处理死亡行为本身
    @SuppressWarnings("deprecation")
    public void onOperatorDead() {
        disconnectWithEntity(Entity.RemovalReason.KILLED, STATUS_REST, RetreatReason.KILLED);
        opeHandler.onRetreat(this);
    }

    // ---[杂类]---

    public void modifyAttribute(boolean remove, IAttributesProvider.MarkedModifier modifier) {
        if (entity != null && status.equals(STATUS_TRACKING)) {
            if (remove) modifier.remove(entity);
            else modifier.attach(entity);
        }
    }

    public ResourceLocation getStatus() {
        return status;
    }

    public void skipResting() {
        if (status.equals(STATUS_REST)) status = STATUS_READY;
    }

    public @Nullable OperatorEntity getEntity() {
        return entity;
    }

    public Optional<EntityFinderInfo> getEntityFinderInfo() {
        return entityFinderInfo;
    }

    public OpeHandler getOpeHandler() {
        return opeHandler;
    }

    public OperatorType getType() {
        return identifier.type();
    }

    public void markLastPos(OperatorEntity entity) {
        entityFinderInfo.ifPresentOrElse(i -> i.posRecorder = new LevelAndPosRecorder(entity), () -> this.entityFinderInfo = Optional.of(new EntityFinderInfo(entity)));
    }

    public void markLastPos(LevelAndPosRecorder recorder) {
        entityFinderInfo.ifPresent(info -> info.posRecorder = recorder);
    }

    public Set<MapCodec<? extends OperatorInfo<?>>> getInfoTypeSet(){
        return infos.keySet();
    }

    public <T extends OperatorInfo<T>> @Nullable OperatorInfo<T> getInfo(MapCodec<T> type){
        return (OperatorInfo<T>) infos.get(type);
    }

    public <T extends OperatorInfo<T>> @Nullable OperatorInfo<T> getInfoOrDefault(MapCodec<T> type, Supplier<T> defaultValue){
        return (OperatorInfo<T>) infos.getOrDefault(type, defaultValue.get());
    }

    public boolean addInfo(OperatorInfo<?> info) {
        if (infos.containsKey(info.codec())) return false;
        infos.put(info.codec(), info);
        return true;
    }

    public boolean removeInfo(OperatorInfo<?> info) {
        if (infos.containsKey(info.codec()) && infos.get(info.codec()) == info) {
            infos.remove(info.codec());
            return true;
        }
        return false;
    }

    public boolean removeInfo(MapCodec<? extends OperatorInfo<?>> info) {
        if (infos.containsKey(info)) {
            infos.remove(info);
            return true;
        }
        return false;
    }

    public boolean containsInfo(MapCodec<? extends OperatorInfo<?>> info) {
        return infos.containsKey(info);
    }


    public record Identifier(OperatorType type, Optional<UUID> uuid) {
        public static final Codec<Identifier> CODEC = RecordCodecBuilder.create(n -> n.group(
                Registries.OPERATOR_TYPE.byNameCodec().fieldOf("type").forGetter(Identifier::type),
                UUIDUtil.CODEC.optionalFieldOf("uuid").forGetter(Identifier::uuid)
        ).apply(n, Identifier::new));

        public Identifier(OperatorType type) {
            this(type, Optional.empty());
        }

        public Identifier(OperatorType type, UUID uuid) {
            this(type, Optional.of(uuid));
        }
    }

    public static class EntityFinderInfo {
        public static final Codec<EntityFinderInfo> CODEC = RecordCodecBuilder.create(n -> n.group(
                UUIDUtil.CODEC.fieldOf("uuid").forGetter(i -> i.entityUUID),
                LevelAndPosRecorder.CODEC.fieldOf("pos").forGetter(i -> i.posRecorder)
        ).apply(n, EntityFinderInfo::new));
        public final UUID entityUUID;
        public @NotNull LevelAndPosRecorder posRecorder;

        public EntityFinderInfo(UUID entityUUID, @NotNull LevelAndPosRecorder posRecorder) {
            this.entityUUID = entityUUID;
            this.posRecorder = posRecorder;
        }

        public EntityFinderInfo(LivingEntity entity) {
            this.entityUUID = entity.getUUID();
            this.posRecorder = new LevelAndPosRecorder(entity);
        }

        public boolean match(OperatorEntity entity) {
            return entity.getUUID().equals(this.entityUUID);
        }
    }
}
