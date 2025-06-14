package com.phasetranscrystal.nonatomic;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.phasetranscrystal.nonatomic.core.OperatorInfo;
import com.phasetranscrystal.nonatomic.core.OperatorType;
import net.minecraft.core.Registry;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.registries.RegistryBuilder;

public class Registries {
    public static final Registry<MapCodec<? extends OperatorInfo<?>>> OPERATOR_INFO = new RegistryBuilder<>(Keys.OPERATOR_INFO).sync(true).create();
    public static final Registry<OperatorType> OPERATOR_TYPE = new RegistryBuilder<>(Keys.OPERATOR_TYPE).sync(true).create();

    public static class Keys{

        public static final ResourceKey<Registry<MapCodec<? extends OperatorInfo<?>>>> OPERATOR_INFO = create("operator_info");
        public static final ResourceKey<Registry<OperatorType>> OPERATOR_TYPE = create("operator_type");

        public static <T> ResourceKey<Registry<T>> create(String name) {
            return ResourceKey.createRegistryKey(ResourceLocation.fromNamespaceAndPath(Nonatomic.MOD_ID, name));
        }
    }


}
