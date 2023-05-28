package de.presti.ree6.backend.service;

import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;
import de.presti.ree6.backend.bot.BotWorker;
import de.presti.ree6.backend.repository.GuildStatsRepository;
import de.presti.ree6.backend.utils.data.container.*;
import de.presti.ree6.sql.SQLSession;
import de.presti.ree6.sql.entities.Recording;
import de.presti.ree6.sql.entities.webhook.Webhook;
import de.presti.ree6.sql.entities.webhook.WebhookLog;
import de.presti.ree6.sql.entities.webhook.WebhookReddit;
import de.presti.ree6.sql.entities.webhook.WebhookWelcome;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.channel.middleman.StandardGuildMessageChannel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

@Service
public class GuildService {

    private final SessionService sessionService;

    private final GuildStatsRepository guildStatsRepository;

    @Autowired
    public GuildService(SessionService sessionService, GuildStatsRepository guildStatsRepository) {
        this.sessionService = sessionService;
        this.guildStatsRepository = guildStatsRepository;
    }

    //region Stats

    public GuildStatsContainer getStats(String sessionIdentifier, String guildId) throws IllegalAccessException {
        GuildContainer guildContainer = sessionService.retrieveGuild(sessionIdentifier, guildId);
        return new GuildStatsContainer(SQLSession.getSqlConnector().getSqlWorker().getInvites(guildId).size(),
                guildStatsRepository.getGuildCommandStatsByGuildId(guildId).stream().map(CommandStatsContainer::new).toList());
    }

    public List<CommandStatsContainer> getCommandStats(String sessionIdentifier, String guildId) throws IllegalAccessException {
        GuildContainer guildContainer = sessionService.retrieveGuild(sessionIdentifier, guildId);
        return guildStatsRepository.getGuildCommandStatsByGuildId(guildId).stream().map(CommandStatsContainer::new).toList();
    }

    public int getInviteCount(String sessionIdentifier, String guildId) throws IllegalAccessException {
        GuildContainer guildContainer = sessionService.retrieveGuild(sessionIdentifier, guildId);
        return SQLSession.getSqlConnector().getSqlWorker().getInvites(guildId).size();
    }

    //endregion

    //region Log channel

    public ChannelContainer getLogChannel(String sessionIdentifier, String guildId) throws IllegalAccessException {
        GuildContainer guildContainer = sessionService.retrieveGuild(sessionIdentifier, guildId, true);
        Webhook webhook = SQLSession.getSqlConnector().getSqlWorker().getLogWebhook(guildId);
        if (webhook == null) {
            return new ChannelContainer();
        }
        return new ChannelContainer(guildContainer.getGuildChannelById(webhook.getChannelId()));
    }

    public void updateLogChannel(String sessionIdentifier, String guildId, String channelId) throws IllegalAccessException {
        GuildContainer guildContainer = sessionService.retrieveGuild(sessionIdentifier, guildId);
        Guild guild = guildContainer.getGuild();
        StandardGuildMessageChannel channel = guild.getChannelById(StandardGuildMessageChannel.class, channelId);

        net.dv8tion.jda.api.entities.Webhook newWebhook = channel.createWebhook("Ree6-Log").complete();

        deleteLogChannel(guild);

        WebhookLog webhook = new WebhookLog(guildId, newWebhook.getId(), newWebhook.getToken());
        SQLSession.getSqlConnector().getSqlWorker().updateEntity(webhook);
    }

    public void removeLogChannel(String sessionIdentifier, String guildId) throws IllegalAccessException {
        deleteWelcomeChannel(sessionService.retrieveGuild(sessionIdentifier, guildId).getGuild());
    }

    private void deleteLogChannel(Guild guild) {
        Webhook webhook = SQLSession.getSqlConnector().getSqlWorker().getLogWebhook(guild.getId());

        if (webhook != null) {
            guild.retrieveWebhooks().queue(c -> c.stream().filter(entry -> entry.getToken() != null)
                    .filter(entry -> entry.getId().equalsIgnoreCase(webhook.getChannelId()) && entry.getToken().equalsIgnoreCase(webhook.getToken()))
                    .forEach(entry -> entry.delete().queue()));
        }
    }

    //endregion

    //region Welcome channel

    public ChannelContainer getWelcomeChannel(String sessionIdentifier, String guildId) throws IllegalAccessException {
        GuildContainer guildContainer = sessionService.retrieveGuild(sessionIdentifier, guildId, true);
        Webhook webhook = SQLSession.getSqlConnector().getSqlWorker().getWelcomeWebhook(guildId);
        if (webhook == null) {
            return new ChannelContainer();
        }
        return new ChannelContainer(guildContainer.getGuildChannelById(webhook.getChannelId()));
    }

    public void updateWelcomeChannel(String sessionIdentifier, String guildId, String channelId) throws IllegalAccessException {
        GuildContainer guildContainer = sessionService.retrieveGuild(sessionIdentifier, guildId);
        Guild guild = guildContainer.getGuild();

        StandardGuildMessageChannel channel = guild.getChannelById(StandardGuildMessageChannel.class, channelId);

        deleteWelcomeChannel(guild);

        net.dv8tion.jda.api.entities.Webhook newWebhook = channel.createWebhook("Ree6-Welcome").complete();

        SQLSession.getSqlConnector().getSqlWorker().updateEntity(new WebhookWelcome(guildId, newWebhook.getId(), newWebhook.getToken()));
    }

    public void removeWelcomeChannel(String sessionIdentifier, String guildId) throws IllegalAccessException {
        deleteWelcomeChannel(sessionService.retrieveGuild(sessionIdentifier, guildId).getGuild());
    }

    private void deleteWelcomeChannel(Guild guild) {
        WebhookWelcome webhook = SQLSession.getSqlConnector().getSqlWorker().getWelcomeWebhook(guild.getId());

        if (webhook != null) {
            guild.retrieveWebhooks().queue(c -> c.stream().filter(entry -> entry.getToken() != null)
                    .filter(entry -> entry.getId().equalsIgnoreCase(webhook.getChannelId()) && entry.getToken().equalsIgnoreCase(webhook.getToken()))
                    .forEach(entry -> entry.delete().queue()));
        }
    }

    //endregion

    //region Reddit Notifications

    public List<NotifierContainer> getRedditNotifier(String sessionIdentifier, String guildId) throws IllegalAccessException {
        GuildContainer guildContainer = sessionService.retrieveGuild(sessionIdentifier, guildId, true);
        List<WebhookReddit> subreddits = SQLSession.getSqlConnector().getSqlWorker().getAllRedditWebhooks(guildId);

        return subreddits.stream().map(subreddit -> new NotifierContainer(subreddit.getSubreddit(), subreddit.getMessage(), guildContainer.getGuild().retrieveWebhooks()
                .complete().stream().filter(c -> c.getId().equals(subreddit.getGuildId())).map(ChannelContainer::new).findFirst().orElse(null))).toList();
    }

    public void addRedditNotifier(String sessionIdentifier, String guildId, String subreddit, String message, String channelId) throws IllegalAccessException {
        GuildContainer guildContainer = sessionService.retrieveGuild(sessionIdentifier, guildId, true);
        Guild guild = guildContainer.getGuild();
        StandardGuildMessageChannel channel = guild.getChannelById(StandardGuildMessageChannel.class, channelId);

        net.dv8tion.jda.api.entities.Webhook newWebhook = channel.createWebhook("Ree6-RedditNotifier-" + subreddit).complete();

        SQLSession.getSqlConnector().getSqlWorker().updateEntity(new WebhookReddit(guildId, subreddit, message, newWebhook.getId(), newWebhook.getToken()));
    }

    // TODO:: make a universal delete method for webhooks, safe code.
    public void removeRedditNotifier(String sessionIdentifier, String guildId, String subreddit) throws IllegalAccessException {
        SQLSession.getSqlConnector().getSqlWorker().removeRedditWebhook(guildId, subreddit);
    }

    //endregion

    //region LevelRewards

    //region Chat

    public List<RoleLevelContainer> getChatAutoRoles(String sessionIdentifier, String guildId) throws IllegalAccessException {
        GuildContainer guildContainer = sessionService.retrieveGuild(sessionIdentifier, guildId, false, true);
        return SQLSession.getSqlConnector().getSqlWorker().getChatLevelRewards(guildId).entrySet().stream().map(x -> new RoleLevelContainer(x.getKey(), guildContainer.getRoleById(x.getValue()))).toList();
    }

    public void addChatAutoRole(String sessionIdentifier, String guildId, String roleId, long level) throws IllegalAccessException {
        GuildContainer guildContainer = sessionService.retrieveGuild(sessionIdentifier, guildId, false, false);
        SQLSession.getSqlConnector().getSqlWorker().addChatLevelReward(guildId, roleId, level);
    }

    public void removeChatAutoRole(String sessionIdentifier, String guildId, long level) throws IllegalAccessException {
        GuildContainer guildContainer = sessionService.retrieveGuild(sessionIdentifier, guildId, false, false);
        SQLSession.getSqlConnector().getSqlWorker().removeChatLevelReward(guildId, level);
    }

    //endregion

    //region Voice

    public List<RoleLevelContainer> getVoiceAutoRoles(String sessionIdentifier, String guildId) throws IllegalAccessException {
        GuildContainer guildContainer = sessionService.retrieveGuild(sessionIdentifier, guildId, false, true);
        return SQLSession.getSqlConnector().getSqlWorker().getVoiceLevelRewards(guildId).entrySet().stream().map(x -> new RoleLevelContainer(x.getKey(), guildContainer.getRoleById(x.getValue()))).toList();
    }

    public void addVoiceAutoRole(String sessionIdentifier, String guildId, String roleId, long level) throws IllegalAccessException {
        GuildContainer guildContainer = sessionService.retrieveGuild(sessionIdentifier, guildId, false, false);
        SQLSession.getSqlConnector().getSqlWorker().addVoiceLevelReward(guildId, roleId, level);
    }

    public void removeVoiceAutoRole(String sessionIdentifier, String guildId, long level) throws IllegalAccessException {
        GuildContainer guildContainer = sessionService.retrieveGuild(sessionIdentifier, guildId, false, false);
        SQLSession.getSqlConnector().getSqlWorker().removeVoiceLevelReward(guildId, level);
    }

    //endregion

    //endregion

    public RecordContainer getRecording(String sessionIdentifier, String recordId) throws IllegalAccessException {
        SessionContainer sessionContainer = sessionService.retrieveSession(sessionIdentifier);
        List<GuildContainer> guilds = sessionService.retrieveGuilds(sessionIdentifier, false);

        Recording recording = SQLSession.getSqlConnector().getSqlWorker().getEntity(new Recording(), "SELECT * FROM Recording WHERE ID=:id", Map.of("id", recordId));

        if (guilds.stream().anyMatch(g -> g.getId().equalsIgnoreCase(recording.getGuildId()))) {
            boolean found = false;

            for (JsonElement element : recording.getJsonArray()) {
                if (element.isJsonPrimitive()) {
                    JsonPrimitive primitive = element.getAsJsonPrimitive();
                    if (primitive.isString() && primitive.getAsString().equalsIgnoreCase(sessionContainer.getUser().getId())) {
                        found = true;
                        break;
                    }
                }
            }

            if (found) {
                SQLSession.getSqlConnector().getSqlWorker().deleteEntity(recording);
                return new RecordContainer(recording);
            } else {
                throw new IllegalAccessException("You were not part of this recording.");
            }
        } else {
            throw new IllegalAccessException("You were not part of the Guild this recording was made in!");
        }
    }

}