package dev.cylox.msm.integration;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;
import dev.cylox.msm.screen.SessionManagerScreen;

public final class MsmModMenuIntegration implements ModMenuApi {
    @Override
    public ConfigScreenFactory<?> getModConfigScreenFactory() {
        return SessionManagerScreen::new;
    }
}
