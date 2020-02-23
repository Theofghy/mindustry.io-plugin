package mindustry.plugin;
import arc.struct.Array;
import mindustry.entities.type.BaseUnit;
import mindustry.entities.type.Player;

import java.io.Serializable;
import java.lang.ref.WeakReference;

public class TempPlayerData implements Serializable {
    WeakReference<Player> playerRef;
    public String origName;
    public Array<BaseUnit> draugPets = new Array<>();
    public int hue;
    public boolean doRainbow;
    public boolean spawnedLichPet;
    public boolean spawnedPowerGen;

    public TempPlayerData(Player p) {
        playerRef = new WeakReference<>(p);
        origName = p.name;
    }

    public TempPlayerData(Player p, String name){
        playerRef = new WeakReference<>(p);
        this.origName = name;
    }

    public void setHue(int i) {
        this.hue = i;
    }
}
