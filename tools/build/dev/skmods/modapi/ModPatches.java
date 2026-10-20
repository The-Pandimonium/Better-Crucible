package dev.skmods.modapi;

/**
 * What a mod does to the game's own classes, written once in {@code mods/<id>/patches/Patches.java} (a class called {@code Patches} that implements
 * this). The build runs it after the mod's classes are compiled and turned back to the game's names, and the classes it changes are written into
 * the mod, where the Knight Launcher puts them in place of the game's.
 *
 * <p>Every name in it is the readable name (see {@code mappings/}): the classes, the methods and the descriptors; the {@link Patcher} turns them
 * into the names the game's jar really has.
 *
 * <pre>
 * public final class Patches implements ModPatches {
 *     public void apply(Patcher p) {
 *         // call HealthLabels.card(card, health) first thing in StatusCard.setHealth(Health)
 *         p.injectAtStart("com.threerings.projectx.dungeon.client.PartyCard$Card", "setHealth", "(Lcom/threerings/projectx/dungeon/data/Health;)V",
 *             Hook.of("dev.skmods.hud.HealthLabels", "card", "(Lcom/threerings/opengl/gui/UserInterface;Lcom/threerings/projectx/dungeon/data/Health;)V", Arg.THIS, Arg.param(0)));
 *     }
 * }
 * </pre>
 */
public interface ModPatches {
    void apply(Patcher patcher) throws Exception;
}
