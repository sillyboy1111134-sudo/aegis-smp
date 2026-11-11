package dev.aegis;

/** Passive ids are listed in UNLOCK ORDER (first = 0 energy, last = max energy). */
public enum ClassType {
    SHACKLED("shackled", "Shackled",
            new String[]{"kb", "slow", "bleed", "shuffle", "enchain"},
            new String[]{"Heavy Frame", "Slowing Strikes", "Bleeding Strikes", "Hotbar Shuffle", "Enchain Proc"},
            "Enchain", "Chain Shot"),
    MOANDER("moander", "Moander",
            new String[]{"wither", "poison", "immune", "rotten", "armor", "skeleton"},
            new String[]{"Withering Touch", "Venom Touch", "Rot Immunity", "Rotten Feast", "Armor Corrosion", "Skeleton Kin"},
            "Drain", "Wither Away"),
    CHRONO("chrono", "Chrono",
            new String[]{"eat", "armor", "haste", "repair", "wither", "regen"},
            new String[]{"Quick Eater", "Armor Corrosion", "Haste", "Self-Repair", "Withering Touch", "Regeneration"},
            "Timestop", "Time Reversal"),
    ICOR("icor", "Icor",
            new String[]{"heal", "bleed", "strength", "cap", "regen", "immune"},
            new String[]{"Vampiric Hits", "Bleeding Strikes", "Strength", "Damage Cap", "Regeneration", "Wither Immunity"},
            "Blood Transfusion", "Lifesteal"),
    BERSERKER("berserker", "Berserker",
            new String[]{"neth", "shield", "strength", "stun", "haste", "timber"},
            new String[]{"Netherite Axes", "Shield Breaker", "Strength", "Crit Stun", "Haste", "Timber"},
            "Gambit", "Rapid Strikes"),
    DOLUS("dolus", "Dolus",
            new String[]{"resist", "gapple", "kb", "ores", "glow", "fortune"},
            new String[]{"Resistance", "Frugal Apples", "Heavy Frame", "Ore Consumption", "Glowing Aura", "Fortune Doubling"},
            "Seismic Toss", null),
    SAMURAI("samurai", "Samurai",
            new String[]{"neth", "combo", "strength", "bleed", "speed", "nofall"},
            new String[]{"Netherite Blades", "Combo Crits", "Strength", "Bleeding Strikes", "Speed", "Featherfall"},
            "Dual Wield", "Swift Slash"),
    YMIR("ymir", "Ymir",
            new String[]{"resist", "shield", "slow", "thorns", "stun", "resist2"},
            new String[]{"Resistance", "Unbreakable Shield", "Frost Retaliation", "Thorned Shield", "Short Stun", "Stunned Resistance"},
            "Forced Duel", "Impervious"),
    VARUNA("varuna", "Varuna",
            new String[]{"regen", "crit", "grace", "trident", "kelp", "riptide"},
            new String[]{"Tidal Regen", "Water Crits", "Aquatic Grace", "Sharp Trident", "Kelp Feast", "Riptide"},
            "Tidal Pull", "Suffocation"),
    AGNI("agni", "Agni",
            new String[]{"ignite", "fireres", "lavaregen", "lavares", "swim", "dmg"},
            new String[]{"Ignite", "Fire Resistance", "Lava Regen", "Lava Resistance", "Lava Swimmer", "Scorching Blows"},
            "Magma Bath", "Fireball"),
    HEPHAESTUS("hephaestus", "Hephaestus",
            new String[]{"neth", "gapple", "armor_less", "resist", "armor_dmg", "repair"},
            new String[]{"Netherite Armor", "Golden Feast", "Light Wear", "Resistance", "Armor Corrosion", "Self-Repair"},
            "Shatter", "Piercing Strike"),
    BOREUS("boreus", "Boreus",
            new String[]{"mace", "wind", "refund", "sculk", "nofall", "speed"},
            new String[]{"Axe Smash", "Straight Winds", "Charge Saver", "Silent Steps", "Featherfall", "Speed"},
            "Breezy Dash", "Overdrive");

    public final String id, display, a1, a2;
    public final String[] passives, passiveNames;

    ClassType(String id, String display, String[] passives, String[] passiveNames, String a1, String a2) {
        this.id = id; this.display = display; this.passives = passives; this.passiveNames = passiveNames;
        this.a1 = a1; this.a2 = a2;
    }

    public String abilityName(int n) { return n == 1 ? a1 : a2; }

    public static ClassType byId(String s) {
        for (ClassType t : values()) if (t.id.equalsIgnoreCase(s) || t.name().equalsIgnoreCase(s)) return t;
        return null;
    }
}
