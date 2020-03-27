package mindustry.plugin;

import arc.files.Fi;
import arc.struct.Array;
import arc.util.CommandHandler;
import mindustry.io.SaveIO;
import mindustry.maps.Map;

import mindustry.entities.type.Player;
import mindustry.gen.Call;
import mindustry.plugin.datas.PlayerData;
import mindustry.plugin.discordcommands.Context;
import net.dv8tion.jda.api.EmbedBuilder;
import redis.clients.jedis.exceptions.JedisConnectionException;

import javax.imageio.ImageIO;
import java.io.*;
import java.util.HashMap;
import java.util.zip.InflaterInputStream;

import static mindustry.Vars.*;
import static mindustry.plugin.Utils.*;
import static mindustry.plugin.ioMain.*;
import static net.dv8tion.jda.api.entities.Message.*;

public class ComCommands {
    public void registerCommands(CommandHandler handler) {
        handler.<Context>register("chat", "<message...>", "Send a message to in-game chat.", (args, ctx) -> {
            if(args[0].length() < chatMessageMaxSize){
                Call.sendMessage("[sky]" + ctx.author.getAsTag() + " @discord >[] " + args[0]);
                ctx.sendEmbed(true, ":mailbox_with_mail: **message sent!**", "``" + escapeCharacters(args[0]) + "``");
            } else{
                ctx.sendEmbed(false, ":exclamation: **message too big!**", "maximum size: **" + chatMessageMaxSize + " characters**");
            }
        });

        handler.<Context>register("maps", "Display all available maps in the playlist.", (args, ctx) -> {
            Array<Map> mapList = maps.customMaps();
            StringBuilder smallMaps = new StringBuilder();
            StringBuilder mediumMaps = new StringBuilder();
            StringBuilder bigMaps = new StringBuilder();

            for(Map map : mapList){
                int size = map.height * map.width;
                if(size <= 62500) { smallMaps.append("**").append(escapeCharacters(map.name())).append("** ").append(map.width).append("x").append(map.height).append("\n"); }
                if(size > 62500 && size < 160000) { mediumMaps.append("**").append(escapeCharacters(map.name())).append("** ").append(map.width).append("x").append(map.height).append("\n"); }
                if(size >= 160000) { bigMaps.append("**").append(escapeCharacters(map.name())).append("** ").append(map.width).append("x").append(map.height).append("\n"); }
            }
            HashMap<String, String> fields = new HashMap<>();
            if(smallMaps.length() > 0){fields.put("small maps", smallMaps.toString()); }
            if(mediumMaps.length() > 0){fields.put("medium maps", mediumMaps.toString()); }
            if(bigMaps.length() > 0){fields.put("big maps", bigMaps.toString()); }

            ctx.sendEmbed(true,":map: **" + mapList.size + " maps** in " + serverName + "'s playlist", fields, true);
        });

        handler.<Context>register("map","<map...>", "Preview/download a map from the playlist.", (args, ctx) -> {
            Map map = getMapBySelector(args[0].trim());
            if (map != null){
                try {
                    ContentHandler.Map visualMap = contentHandler.parseMap(map.file.read());
                    Fi mapFile = map.file;
                    File imageFile = new File(assets + "image_" + mapFile.name().replaceAll(".msav", ".png"));
                    ImageIO.write(visualMap.image, "png", imageFile);

                    EmbedBuilder eb = new EmbedBuilder().setColor(Pals.success).setTitle(":map: **" + escapeCharacters(map.name()) + "** <" + map.width + "x" + map.height + ">").setDescription(escapeCharacters(map.description())).setAuthor(escapeCharacters(map.author()));
                    eb.setImage("attachment://" + imageFile.getName());
                    ctx.channel.sendFile(mapFile.file()).addFile(imageFile).embed(eb.build()).queue();
                } catch (IOException e) {
                    ctx.sendEmbed(false, ":eyes: **internal server error**");
                    e.printStackTrace();
                }
            }else{
                ctx.sendEmbed(false, ":mag: map **" + escapeCharacters(args[0]) + "** not found");
            }
        });

        handler.<Context>register("submitmap", "Submit a map to be added to the server playlist.", (args, ctx) -> {
            Attachment attachment = (ctx.event.getMessage().getAttachments().size() == 1 ? ctx.event.getMessage().getAttachments().get(0) : null);
            if (attachment == null) {
                ctx.sendEmbed(false, ":link: **you need to attach a valid .msav file!**");
                return;
            }
            File mapFile = new File(assets + attachment.getFileName());
            attachment.downloadToFile(mapFile).thenAccept(file -> {
                Fi fi = new Fi(mapFile);
                byte[] bytes = fi.readBytes();

                DataInputStream dis = new DataInputStream(new InflaterInputStream(new ByteArrayInputStream(bytes)));
                if (attachment.getFileName().endsWith(".msav") && SaveIO.isSaveValid(dis)) {
                    try {
                        OutputStream os = new FileOutputStream(mapFile);
                        os.write(bytes);

                        ContentHandler.Map map = contentHandler.parseMap(fi.read());
                        File imageFile = new File(assets + "image_" + attachment.getFileName().replaceAll(".msav", ".png"));
                        ImageIO.write(map.image, "png", imageFile);

                        EmbedBuilder eb = new EmbedBuilder();
                        eb.setColor(Pals.progress);
                        eb.setTitle(escapeCharacters(map.name));
                        eb.setDescription(map.description);
                        eb.setAuthor(ctx.author.getAsTag(), null, ctx.author.getAvatarUrl());
                        eb.setFooter("react to this message accordingly to approve/disapprove this map.");
                        eb.setImage("attachment://" + imageFile.getName());

                        mapSubmissions.sendFile(mapFile).addFile(imageFile).embed(eb.build()).queue(message -> {
                            message.addReaction("true:693162979616751616").queue();
                            message.addReaction("false:693162961761730723").queue();
                        });

                        ctx.sendEmbed(true, ":map: **" + escapeCharacters(map.name) + "** submitted successfully!", "a moderator will soon approve or disapprove your map.");
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                } else {
                    ctx.sendEmbed(false, ":interrobang: **attachment invalid or corrupted!**");
                }
            });
        });

        handler.<Context>register("players","Get all online in-game players.", (args, ctx) -> {
            HashMap<Integer, String> playersInRank = new HashMap<>();
            EmbedBuilder eb = new EmbedBuilder();
            eb.setColor(Pals.progress);
            eb.setTitle(":satellite: **players online: **" + playerGroup.all().size);
            for(int rank : rankNames.keySet()){
                playersInRank.put(rank, "");
            }
            for(Player p : playerGroup.all()){
                try {
                    PlayerData pd = getData(p.uuid);
                    if (pd != null) {
                        playersInRank.put(pd.rank, playersInRank.get(pd.rank) + escapeCharacters(p.name) + "\n");
                    }
                } catch(JedisConnectionException e){
                    e.printStackTrace();
                }
            }
            for(int rank : rankNames.keySet()){
                if(playersInRank.get(rank).length() > 0) {
                    eb.addField(rankNames.get(rank).name, playersInRank.get(rank), true);
                }
            }
            ctx.sendEmbed(eb);
        });

        handler.<Context>register("status", "View the status of this server.", (args, ctx) -> {
            HashMap<String, String> fields = new HashMap<>();
            fields.put("players", String.valueOf(playerGroup.all().size));
            fields.put("map", escapeCharacters(world.getMap().name()));
            fields.put("wave", String.valueOf(state.wave));

            ctx.sendEmbed(true, ":desktop: **" + serverName + "**", fields, false);
        });

    }
}
