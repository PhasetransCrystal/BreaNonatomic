package com.phasetranscrystal.nonatomic.event;

import com.phasetranscrystal.nonatomic.core.Operator;
import com.phasetranscrystal.nonatomic.core.OperatorEntity;
import com.phasetranscrystal.nonatomic.core.OperatorInfo;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.Event;
import net.neoforged.bus.api.ICancellableEvent;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.annotation.Nonnull;

/**
 * 干员事件用于处理一系列干员的基础行为与玩家行为对干员的影响。
 * <p>
 * 所有事件均发布在{@link net.neoforged.neoforge.common.NeoForge#EVENT_BUS 游戏主线}上，并只在{@link net.neoforged.fml.LogicalSide#SERVER 逻辑服务端}上生效。
 * <p>
 * OperatorEvents are used to handle situations about operator's behavior and influences from player.
 * <p>
 * All events are fired on {@link net.neoforged.neoforge.common.NeoForge#EVENT_BUS GameBus} and only on {@link net.neoforged.fml.LogicalSide#SERVER logical server}.
 */
public abstract class OperatorEvent extends Event {
    public final Operator operator;

    public OperatorEvent(final Operator operator) {
        this.operator = operator;
    }

    /**
     * 在玩家登出前触发，包含玩家拥有的所有干员。重部署标记只在跟随状态下被使用。
     * <p>
     * Fired before player logout, include all the operators player have.
     * Only operators in TRACKING status will use redeploy flag.
     *
     * @see Operator#logout()
     */
    public static class OnPlayerLogout extends OperatorEvent {
        private boolean allowRedeployForTracking = true;
        public final ServerPlayer player;
        public final @Nullable OperatorEntity entity;

        public OnPlayerLogout(ServerPlayer player, Operator operator, @Nullable OperatorEntity entity) {
            super(operator);
            this.player = player;
            this.entity = entity;
        }

        public void preventTrackingRedeploy() {
            allowRedeployForTracking = false;
        }

        public void setTrackingRedeploy(boolean v) {
            allowRedeployForTracking = v;
        }

        public boolean result() {
            return allowRedeployForTracking;
        }
    }

    /**
     * 用于在实体加载的时候寻找并捕获与其匹配的干员并同步数据。
     * <p>
     * To find and reference the right operator instance when operator entities create or load.
     *
     * @see com.phasetranscrystal.nonatomic.EventHooks#findOperator(MinecraftServer, OperatorEntity) EventHooks#findOperator(MinecraftServer, OperatorEntity)
     */
    public static class FindOperator extends Event {
        private Operator result;
        public final MinecraftServer server;
        public final OperatorEntity entity;

        //注：此时实体还没有被添加进世界 也还没有验证合法性
        //NOTE: now entity is neither add to world nor check its legality.
        public FindOperator(MinecraftServer server, OperatorEntity entity) {
            this.server = server;
            this.entity = entity;
        }

        public Operator getResult() {
            return result;
        }

        public void setResult(Operator result) {
            this.result = result;
        }

        public boolean found() {
            return result != null;
        }
    }

    /**
     * 部署事件，在干员实体实例尝试加入世界时触发。
     * <p>
     * Deploy event, fired when a operator entity instance adding to world.
     *
     * @see com.phasetranscrystal.nonatomic.GameBusConsumer#checkOperatorEntity(EntityJoinLevelEvent) GameBusConsumer#checkOperatorEntity(EntityJoinLevelEvent)
     */
    public static abstract class Deploy extends OperatorEvent {
        public final OperatorEntity entity;
        public final @Nonnull ServerPlayer player;

        public Deploy(Operator operator, OperatorEntity entity, @NotNull ServerPlayer player) {
            super(operator);
            this.entity = entity;
            this.player = player;
        }

        /**
         * 在部署前触发，可以用于判断是否应该部署。此事件{@link ICancellableEvent 可以被撤销}，撤销后干员不会被部署。
         * <p>
         * Fired before entity deployed, use to adjust if operator should deploy.
         * This event is {@link ICancellableEvent Cancellable} then the operator won't deploy.
         */
        public static class Pre extends Deploy implements ICancellableEvent {
            public Pre(Operator operator, OperatorEntity entity, ServerPlayer player) {
                super(operator, entity, player);
            }
        }

        /**
         * 在部署后触发，可以对部署的干员实体进行修改。
         * <p>
         * Fired after operator deployed, use to modify the operator entity.
         */
        public static class Post extends Deploy {
            public Post(Operator operator, OperatorEntity entity, ServerPlayer player) {
                super(operator, entity, player);
            }
        }
    }

    /**
     * 在部署失败时触发，提供的干员实体不会被加入世界。如果失败代码为-7，<code>operator</code>将为null。
     * <p>
     * Fired after operator deployed, use to modify the operator entity.
     * If failure code is -7, <code>operator</code> will be null.
     */
    public static class DeployFailed extends OperatorEvent {
        public final int flag;
        public final ServerPlayer player;

        public DeployFailed(Operator operator, ServerPlayer player, int flag) {
            super(operator);
            this.player = player;
            this.flag = flag;
        }

        /**
         * 失败原因代码。<p> The code for failure reason.
         *
         * @return -1 -> 玩家不存在 Player not exist<p>  -2 -> 实体已存在 Entity existed<p>  -3 -> 状态不合法 Illegal status<p>
         * -4 -> 部署被实体类型或事件否决 Prevent by entity type or event<p>  -5 -> 部署区无空位 No place in deploying list<p>
         * -6 -> 未找到合理的部署位置 No valid deploy position found<p> -7 -> 干员不存在 Operator not exist.
         */
        public int getFlag() {
            return flag;
        }
    }

    /**
     * 在干员实体加载完成后触发。
     * <p>
     * Fired after operator entity load.
     */
    public static class OperatorLoaded extends OperatorEvent {
        public final OperatorEntity entity;
        public final ServerPlayer player;

        public OperatorLoaded(Operator operator, OperatorEntity entity, ServerPlayer player) {
            super(operator);
            this.entity = entity;
            this.player = player;
        }
    }

    /**
     * 在干员撤退时触发。
     * <p>
     * Fired when operator retreat.
     */
    public static abstract class Retreat extends OperatorEvent {
        public final Operator.RetreatReason reason;

        public Retreat(Operator operator, Operator.RetreatReason reason) {
            super(operator);
            this.reason = reason;
        }

        /**
         * 在干员撤退前触发，用于决定是否允许干员撤退。不是所有情况都会触发该事件，例如跟随状态干员在玩家离线时撤退和干员死亡。
         * <p>
         * 此事件可以被{@link ICancellableEvent 撤销}且有{@link Pre#finalStatus 返回值}决定干员的最终状态。
         * <p>
         * Fired before operator retreat, use to adjust if the operator should retreat.
         * Notes it won't fire in all cases, such as tracking operators retreat when player logout or operator dead.
         * <p>
         * This event is {@link ICancellableEvent Cancellable} and has a result {@link Pre#finalStatus FinalStatus}
         * which use to decide the operator status to save.
         */
        public static class Pre extends Retreat implements ICancellableEvent {
            private ResourceLocation finalStatus;
            public final ServerPlayer player;

            public Pre(ServerPlayer player, Operator operator, Operator.RetreatReason reason) {
                super(operator, reason);
                this.player = player;
                this.finalStatus = (operator.getStatus() == Operator.STATUS_WORKING || operator.getStatus() == Operator.STATUS_ALERT) ? Operator.STATUS_READY : Operator.STATUS_REST;
            }

            public void setStatues(ResourceLocation status) {
                this.finalStatus = status;
            }

            public ResourceLocation getFinalStatus() {
                return finalStatus;
            }
        }

        /**
         * 在干员撤退后触发，包含{@link Pre Pre事件}中不会触发的撤退行为。
         * <p>
         * Fired after operator retreat, include cases won't fire in {@link Pre} Event.
         */
        public static class Post extends Retreat {
            public Post(Operator operator, Operator.RetreatReason reason) {
                super(operator, reason);
            }
        }
    }

    /**
     * 是否允许干员在该状态下合并指定的信息类型。此事件{@link ICancellableEvent 可取消}，设置为{@link MergeData#delete 删除}时会自动取消事件。
     * <p>
     * 事件未取消会使数据被合并后删除，如果取消且被标记为删除数据会被直接删除。
     * <p>
     * If the external operator info should merge. This event is {@link ICancellableEvent cancellable},
     * event will auto cancel when {@link MergeData#delete delete} is marked.
     * <p>
     * Uncanceled event means the info will be deleted after merge, while delete-marked and canceled event will directly delete the info.
     */
    public static class MergeData extends OperatorEvent implements ICancellableEvent {
        public final Operator.RetreatReason reason;
        public final OperatorEntity entity;
        public final OperatorInfo<?> info;
        private boolean delete;

        public MergeData(Operator.RetreatReason reason, OperatorEntity entity, Operator operator, OperatorInfo<?> info) {
            super(operator);
            this.reason = reason;
            this.entity = entity;
            this.info = info;
        }

        public boolean isDelete() {
            return delete;
        }

        public void markDelete() {
            delete = true;
            setCanceled(true);
        }

        public void setDelete(boolean delete) {
            this.delete = delete;
            setCanceled(delete);
        }
    }

    /**
     * 评估干员在切换到状态时是否占用对应的部署位置。
     * <p>
     * Judge if the operator should take deploying place.
     */
    public static class JudgeDeployingPlace extends OperatorEvent {
        public final ResourceLocation expectStatus;
        private boolean result;

        public JudgeDeployingPlace(Operator operator, ResourceLocation expectStatus) {
            super(operator);
            this.expectStatus = expectStatus;
            this.result = expectStatus.equals(Operator.STATUS_TRACKING);
        }

        public void setResult(boolean result) {
            this.result = result;
        }

        public boolean getResult() {
            return result;
        }
    }
}
