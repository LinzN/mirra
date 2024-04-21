package de.linzn.mirra.discord.listener;


import de.linzn.mirra.MirraPlugin;
import de.stem.stemSystem.STEMSystemApp;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.entities.channel.ChannelType;
import net.dv8tion.jda.api.entities.channel.middleman.MessageChannel;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;

import java.util.List;

public class DiscordReceiveListener extends ListenerAdapter {

    @Override
    public void onMessageReceived(MessageReceivedEvent event) {
        if (event.isFromType(ChannelType.PRIVATE)) {
            if (event.getAuthor() != MirraPlugin.mirraPlugin.getDiscordManager().getJda().getSelfUser()) {
                String inputData = event.getMessage().getContentDisplay();
                User user = event.getAuthor();
                this.assignGPTModel(user.getName(), inputData, event.getChannel());
            }
        }
    }

    private void assignGPTModel(String sender, String content, MessageChannel channel) {
        STEMSystemApp.LOGGER.INFO("Receive Discord input for AI model");
        List<String> input = MirraPlugin.mirraPlugin.getAiManager().getDefaultModel().buildMessageBlock(sender, content, "DISCORD");
        String chatMessage = MirraPlugin.mirraPlugin.getAiManager().getDefaultModel().requestChatCompletion(input, channel.getId());
        STEMSystemApp.LOGGER.INFO("Response fom AI model received.");
        STEMSystemApp.LOGGER.CORE(chatMessage);
        channel.sendMessage(chatMessage).complete();
    }

}
