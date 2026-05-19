package com.nnpg.glazed;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import meteordevelopment.meteorclient.systems.commands.Command;
import net.minecraft.command.CommandSource;
import net.minecraft.util.math.ChunkPos;
import java.util.List;

import static com.mojang.brigadier.Command.SINGLE_SUCCESS;

public class ChunkSyncCommand extends Command {

    // Khởi tạo tên lệnh là "chunksync"
    public ChunkSyncCommand() {
        super("chunksync", "Dieu khien Chunk Sync Exploit.", "cs");
    }

    @Override
    public void build(LiteralArgumentBuilder<CommandSource> builder) {
        
        // Khi người chơi chỉ gõ: /chunksync
        builder.executes(context -> {
            info("--- Chunk Sync Exploit Commands ---");
            info("/chunksync bypass <x> <z> - Bypass chunk verification");
            info("/chunksync resync <x> <z> - Force chunk resync");
            info("/chunksync dump - Dump modified chunks");
            return SINGLE_SUCCESS;
        });

        // Khi người chơi gõ: /chunksync bypass <x> <z>
        builder.then(literal("bypass")
            .then(argument("x", IntegerArgumentType.integer())
            .then(argument("z", IntegerArgumentType.integer())
            .executes(context -> {
                int x = IntegerArgumentType.getInteger(context, "x");
                int z = IntegerArgumentType.getInteger(context, "z");
                
                // Gọi hàm logic của bạn
                ChunkSyncBypass.bypassChunkVerification(mc.world, x, z);
                info("Chunk bypass executed tai X: " + x + ", Z: " + z);
                
                return SINGLE_SUCCESS;
            }))));

        // Khi người chơi gõ: /chunksync resync <x> <z>
        builder.then(literal("resync")
            .then(argument("x", IntegerArgumentType.integer())
            .then(argument("z", IntegerArgumentType.integer())
            .executes(context -> {
                int x = IntegerArgumentType.getInteger(context, "x");
                int z = IntegerArgumentType.getInteger(context, "z");
                
                ChunkSyncExploit.getInstance().forceChunkUpdate(new ChunkPos(x, z));
                info("Forced chunk resync tai X: " + x + ", Z: " + z);
                
                return SINGLE_SUCCESS;
            }))));

        // Khi người chơi gõ: /chunksync dump
        builder.then(literal("dump")
            .executes(context -> {
                List<ChunkPos> modified = ChunkSyncExploit.getInstance().getStateManager().getModifiedChunks();
                info("Modified chunks: " + modified.size());
                for (ChunkPos pos : modified) {
                    info("  " + pos.x + ", " + pos.z);
                }
                return SINGLE_SUCCESS;
            }));
    }
}
