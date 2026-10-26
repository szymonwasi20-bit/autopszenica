package pl.autopszenica;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.text.Text;

/**
 * AutoPszenica - klientowy mod Fabric 1.21.4.
 * Komenda: /autopszenica  (wlacz/wylacz)
 *          /autopszenica on | off | status
 *          /autopszenica stacks <1-36>   (po ilu stackach pszenicy odkladac do skrzyni, domyslnie 5)
 *          /autopszenica walk on|off     (chodzenie do pszenicy, domyslnie on)
 */
public class AutoPszenicaClient implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        ClientTickEvents.END_CLIENT_TICK.register(FarmBot::tick);

        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
            dispatcher.register(
                ClientCommandManager.literal("autopszenica")
                    .executes(ctx -> {
                        FarmBot.setEnabled(!FarmBot.isEnabled());
                        ctx.getSource().sendFeedback(msg(FarmBot.isEnabled()
                            ? "§aAutoPszenica WŁĄCZONA"
                            : "§cAutoPszenica WYŁĄCZONA"));
                        return 1;
                    })
                    .then(ClientCommandManager.literal("on").executes(ctx -> {
                        FarmBot.setEnabled(true);
                        ctx.getSource().sendFeedback(msg("§aAutoPszenica WŁĄCZONA"));
                        return 1;
                    }))
                    .then(ClientCommandManager.literal("off").executes(ctx -> {
                        FarmBot.setEnabled(false);
                        ctx.getSource().sendFeedback(msg("§cAutoPszenica WYŁĄCZONA"));
                        return 1;
                    }))
                    .then(ClientCommandManager.literal("status").executes(ctx -> {
                        ctx.getSource().sendFeedback(msg("§eStatus: " + (FarmBot.isEnabled() ? "ON" : "OFF")
                            + " | stan: " + FarmBot.stateName()
                            + " | próg odkładania: " + FarmBot.getStacksThreshold() + " stacków"
                            + " | chodzenie: " + (FarmBot.isWalk() ? "ON" : "OFF")));
                        return 1;
                    }))
                    .then(ClientCommandManager.literal("stacks")
                        .then(ClientCommandManager.argument("ilosc", IntegerArgumentType.integer(1, 36))
                            .executes(ctx -> {
                                int n = IntegerArgumentType.getInteger(ctx, "ilosc");
                                FarmBot.setStacksThreshold(n);
                                ctx.getSource().sendFeedback(msg("§eOdkładam po " + n + " stackach pszenicy (" + (n * 64) + " szt.)"));
                                return 1;
                            })))
                    .then(ClientCommandManager.literal("walk")
                        .then(ClientCommandManager.literal("on").executes(ctx -> {
                            FarmBot.setWalk(true);
                            ctx.getSource().sendFeedback(msg("§eChodzenie: ON"));
                            return 1;
                        }))
                        .then(ClientCommandManager.literal("off").executes(ctx -> {
                            FarmBot.setWalk(false);
                            ctx.getSource().sendFeedback(msg("§eChodzenie: OFF (stoisz w miejscu)"));
                            return 1;
                        })))
            )
        );
    }

    static Text msg(String s) {
        return Text.literal("[AutoPszenica] " + s);
    }
}
