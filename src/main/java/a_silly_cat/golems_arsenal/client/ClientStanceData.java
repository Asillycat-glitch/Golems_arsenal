package a_silly_cat.golems_arsenal.client;

/** Client-side cache of the player's stance gauge, synced from the server for HUD rendering. */
public final class ClientStanceData {
    public static int stacks;
    public static double progress;
    public static boolean charged;

    private ClientStanceData() {
    }
}
