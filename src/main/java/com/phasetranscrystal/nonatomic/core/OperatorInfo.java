package com.phasetranscrystal.nonatomic.core;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.phasetranscrystal.nonatomic.Registries;
import com.phasetranscrystal.nonatomic.core.info.IBelongingOperatorProvider;
import org.lwjgl.system.NonnullDefault;

public abstract class OperatorInfo<T extends OperatorInfo<T>> implements IBelongingOperatorProvider {
    public static final Codec<OperatorInfo<?>> CODEC = Registries.OPERATOR_INFO.byNameCodec().dispatch(OperatorInfo::codec, i -> i);


    //empty -> 该数据为外部数据  否则为干员持久化数据
    @NonnullDefault
    public Operator operator;


    public abstract MapCodec<T> codec();

    /**
     * 标记数据合并。请注意完成合并后将传入信息进行清理。
     *
     * @param newData 实体保有的数据，用于和此数据合并
     * @return 是否成功合并
     */
    public abstract boolean merge(final T newData);

    public abstract T createExternal();

    public abstract T copy();

    public void login() {
    }

    public void init(Operator operator) {
        this.operator = operator;
    }

    public void logout() {
    }

    public void entityInit(OperatorEntity entity) {
    }

    public void entityCreated(OperatorEntity entity) {
        entityInit(entity);
    }


    public Operator getBelonging() {
        return operator;
    }


}
