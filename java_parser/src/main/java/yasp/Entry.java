package yasp;

public class Entry {
    //result fields
    public Integer time;
    public String type;
    public Integer player_slot;
    public String key;
    public Integer value;
    public Integer team;
    //data fields
    public String unit;
    public Integer slot;
    //chat event fields
    public Integer player1;
    public Integer player2;
    //combat log fields
    public String attackername;
    public String targetname;
    public String sourcename;
    public String targetsourcename;
    public Boolean attackerhero;
    public Boolean targethero;
    public Boolean attackerillusion;
    public Boolean targetillusion;
    public String inflictor;
    public Integer gold_reason;
    public Integer xp_reason;
    public String valuename;
    //entity fields
    public Integer gold;
    public Integer lh;
    public Integer xp;
    public Integer x;
    public Integer y;
    public Float stuns;
    //public Boolean hasPredictedVictory;
    public boolean interval;
    public boolean max;
    
    public Entry() {
    }

    public Entry(Integer time) {
        this.time = time;
    }
}

public class OutputEntry {
    public Integer time;
    public String type;
    public Integer player_slot;
    public String key;
    public Integer value;
    public Integer team;
    
    public OutputEntry(Entry e){
        this.time = e.time;
        this.type = e.type;
        this.player_slot = e.player_slot;
        this.key = e.key;
        this.value = e.value;
        this.team = e.team;
    }
}
