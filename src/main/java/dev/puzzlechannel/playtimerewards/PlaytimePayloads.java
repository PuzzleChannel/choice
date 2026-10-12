package dev.puzzlechannel.playtimerewards;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

public final class PlaytimePayloads {
    public static final CustomPacketPayload.Type<State> STATE_TYPE =
            new CustomPacketPayload.Type<>(Identifier.parse("daily_playtime_rewards:state"));
    public static final CustomPacketPayload.Type<Claim> CLAIM_TYPE =
            new CustomPacketPayload.Type<>(Identifier.parse("daily_playtime_rewards:claim"));

    public record State(int seconds, int claimedMask, int secondsUntilReset, String rewardConfig) implements CustomPacketPayload {
        public static final StreamCodec<RegistryFriendlyByteBuf, State> CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, State::seconds,
                ByteBufCodecs.VAR_INT, State::claimedMask,
                ByteBufCodecs.VAR_INT, State::secondsUntilReset,
                ByteBufCodecs.STRING_UTF8, State::rewardConfig,
                State::new
        );

        @Override
        public Type<? extends CustomPacketPayload> type() { return STATE_TYPE; }
    }

    public record Claim(int rewardIndex) implements CustomPacketPayload {
        public static final StreamCodec<RegistryFriendlyByteBuf, Claim> CODEC =
                StreamCodec.composite(ByteBufCodecs.VAR_INT, Claim::rewardIndex, Claim::new);
        @Override
        public Type<? extends CustomPacketPayload> type() { return CLAIM_TYPE; }
    }

    private PlaytimePayloads() { }
}
