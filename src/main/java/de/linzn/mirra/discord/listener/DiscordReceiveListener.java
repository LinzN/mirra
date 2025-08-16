package de.linzn.mirra.discord.listener;


import de.linzn.mirra.MirraPlugin;
import de.linzn.mirra.identitySystem.IdentityGuest;
import de.linzn.mirra.identitySystem.IdentityUser;
import de.linzn.mirra.identitySystem.TokenSource;
import de.linzn.mirra.identitySystem.UserToken;
import de.stem.stemSystem.STEMSystemApp;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.entities.channel.ChannelType;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
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
                this.assignGPTModel(user.getName(), inputData, user);
            }
        }
    }

    private void assignGPTModel(String sender, String content, User user) {
        STEMSystemApp.LOGGER.INFO("Receive Discord input for AI model");
        UserToken userToken = MirraPlugin.mirraPlugin.getIdentityManager().getOrCreateUserToken(user.getId(), TokenSource.DISCORD);
        IdentityUser identityUser = MirraPlugin.mirraPlugin.getIdentityManager().getIdentityUserByToken(userToken);
        if (identityUser instanceof IdentityGuest) {
            ((IdentityGuest) identityUser).setGuestName(sender);
        }
        List<String> input = MirraPlugin.mirraPlugin.getAiManager().getDefaultModel().buildMessageBlock(identityUser.getIdentityName(), content, userToken.getSource().name());
        String chatMessage = MirraPlugin.mirraPlugin.getAiManager().getDefaultModel().requestChatCompletion(input, userToken, sender);
        STEMSystemApp.LOGGER.INFO("Response fom AI model received.");
        STEMSystemApp.LOGGER.CORE(chatMessage);
        //MirraPlugin.mirraPlugin.getDiscordManager().getJda().retrieveUserById(user.getId()).complete().openPrivateChannel().complete().sendMessage(chatMessage).complete();

        int maxLength = 1900;
        if (chatMessage.length() > maxLength) {
            int start = 0;
            int end = maxLength;

            while (start < chatMessage.length()) {

                if (end > chatMessage.length()) {
                    end = chatMessage.length();
                }

                String part = chatMessage.substring(start, end);
                MirraPlugin.mirraPlugin.getDiscordManager().getJda().retrieveUserById(user.getId()).complete()
                        .openPrivateChannel().complete().sendMessage(part).complete();
                start = end;
                end = start + maxLength;
            }
        } else {
            MirraPlugin.mirraPlugin.getDiscordManager().getJda().retrieveUserById(user.getId()).complete()
                    .openPrivateChannel().complete().sendMessage(chatMessage).complete();
        }
    }

    @Override
    public void onSlashCommandInteraction(SlashCommandInteractionEvent event) {
        if (event.getName().equals("ping")) {
            event.reply("pong").queue(); // reply immediately
        }
    }
}
