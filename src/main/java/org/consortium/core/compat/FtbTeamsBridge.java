package org.consortium.core.compat;

import net.neoforged.fml.ModList;
import org.consortium.core.ConsortiumCore;

import java.util.Set;

/**
 * FTB Teams integration (specification v0.2, section 4): the party stage copy of PROGRESSION 2. The FTB Teams
 * classes are only touched inside {@link FtbTeamsCalls}, which is loaded only when the mod is present, so this class
 * is safe on a server or client without FTB Teams.
 *
 * <p>Why {@code TeamEvent.CREATED}: {@code TeamManagerImpl.createParty} (2101.1.11) fires it from
 * {@code createPartyTeamInternal} on the still empty party, then moves the creator in ({@code setEffectiveTeam},
 * {@code addMember}, {@code removeMember}) and last posts {@code PLAYER_CHANGED}, where Chapters (registered before
 * this mod: it orders itself BEFORE ftbteams, this mod AFTER) re-syncs the creator and audits the inventory. Copying
 * the stages in {@code CREATED} lands before that audit, so the creator keeps the items the phase stages unlock. No
 * {@code PROPERTIES_CHANGED} and no {@code syncOnePropertyToTeam} are needed at that point (no member yet), and
 * {@code AbstractTeam.onCreated} saves the manager right after the event. {@code TeamStagesHelper} is never used:
 * its add and remove mutate the {@code HashSet} instance every never-set team shares as its default.
 */
public final class FtbTeamsBridge {
    public static final String MOD_ID = "ftbteams";

    private static Boolean present;

    private FtbTeamsBridge() {
    }

    /** True when FTB Teams is loaded; evaluated once. */
    public static boolean available() {
        if (present == null) {
            present = ModList.get().isLoaded(MOD_ID);
        }
        return present;
    }

    /** Mod construction: registers the {@code TeamEvent.CREATED} listener when FTB Teams is present. */
    public static void install() {
        if (!available()) {
            ConsortiumCore.LOGGER.info("FTB Teams is not present: the party stage copy is off");
            return;
        }
        try {
            FtbTeamsCalls.register();
        } catch (Throwable t) {
            ConsortiumCore.LOGGER.error("FTB Teams party stage copy could not be installed", t);
        }
    }

    /** Isolated so the JVM only resolves the FTB Teams classes when this class is loaded. */
    private static final class FtbTeamsCalls {
        static void register() {
            dev.ftb.mods.ftbteams.api.event.TeamEvent.CREATED.register(FtbTeamsCalls::onTeamCreated);
            ConsortiumCore.LOGGER.info("FTB Teams party stage copy installed (TeamEvent.CREATED)");
        }

        static void onTeamCreated(dev.ftb.mods.ftbteams.api.event.TeamCreatedEvent event) {
            try {
                dev.ftb.mods.ftbteams.api.Team party = event.getTeam();
                net.minecraft.server.level.ServerPlayer creator = event.getCreator();
                // CREATED also fires for player teams (first login) and server teams; it never fires with a null creator.
                if (party == null || !party.isPartyTeam() || creator == null) {
                    return;
                }
                dev.ftb.mods.ftbteams.api.FTBTeamsAPI.API api = dev.ftb.mods.ftbteams.api.FTBTeamsAPI.api();
                if (!api.isManagerLoaded()) {
                    return;
                }
                // The creator's effective team is still the personal PlayerTeam: setEffectiveTeam runs after CREATED.
                dev.ftb.mods.ftbteams.api.Team personal = api.getManager().getTeamForPlayer(creator).orElse(null);
                if (personal == null || !personal.isPlayerTeam() || personal == party) {
                    return;
                }
                Set<String> raw = personal.getProperty(dev.ftb.mods.ftbteams.api.property.TeamProperties.TEAM_STAGES);
                // The shared default HashSet instance means "never set": treat it as empty and never mutate it.
                boolean unset = raw == null || raw == dev.ftb.mods.ftbteams.api.property.TeamProperties.TEAM_STAGES.getDefaultValue();
                Set<String> copy = GuardRules.consortiumStages(unset ? null : raw);
                party.setProperty(dev.ftb.mods.ftbteams.api.property.TeamProperties.TEAM_STAGES, copy);
                ConsortiumCore.LOGGER.info("Copied {} consortium stages to party {}", copy.size(), partyName(party));
            } catch (Throwable t) {
                ConsortiumCore.LOGGER.error("Party stage copy failed: the new party starts without stages", t);
            }
        }

        private static String partyName(dev.ftb.mods.ftbteams.api.Team party) {
            try {
                net.minecraft.network.chat.Component name = party.getName();
                String text = name == null ? "" : name.getString();
                return text.isBlank() ? party.getShortName() : text;
            } catch (RuntimeException e) {
                return party.getId().toString();
            }
        }
    }
}
