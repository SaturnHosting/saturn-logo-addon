package lat.saturn.addon;

import lat.saturn.addon.modules.VerticalBuilder;
import com.mojang.logging.LogUtils;
import meteordevelopment.meteorclient.addons.GithubRepo;
import meteordevelopment.meteorclient.addons.MeteorAddon;
import meteordevelopment.meteorclient.commands.Commands;
import meteordevelopment.meteorclient.systems.hud.Hud;
import meteordevelopment.meteorclient.systems.hud.HudGroup;
import meteordevelopment.meteorclient.systems.modules.Category;
import meteordevelopment.meteorclient.systems.modules.Modules;
import org.slf4j.Logger;

public class LogoBuilder extends MeteorAddon {
    public static final Logger LOG = LogUtils.getLogger();
    public static final Category CATEGORY = new Category("Saturn");
    public static final HudGroup HUD_GROUP = new HudGroup("Saturn");

    @Override
    public void onInitialize() {
        LOG.info("Initializing Meteor Saturn Logo Builder");

        // Modules.get().add(new LogoBuilder());
        Modules.get().add(new VerticalBuilder());
    }

    @Override
    public void onRegisterCategories() {
        Modules.registerCategory(CATEGORY);
    }

    @Override
    public String getPackage() {
        return "lat.saturn.addon";
    }

    @Override
    public GithubRepo getRepo() {
        return new GithubRepo("SaturnHosting", "saturn-logo-addon");
    }
}