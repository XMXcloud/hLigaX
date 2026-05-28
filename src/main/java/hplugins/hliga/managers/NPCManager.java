package hplugins.hliga.managers;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import lombok.Getter;
import lombok.Setter;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;

import hplugins.hliga.Main;
import hplugins.hliga.models.ClanPoints;
import hplugins.hliga.models.GenericClan;
import hplugins.hliga.utils.LogUtils;
import hplugins.hliga.utils.NumberFormatter;
import net.citizensnpcs.api.CitizensAPI;
import net.citizensnpcs.api.npc.NPC;
import net.citizensnpcs.api.npc.NPCRegistry;

/**
 * Gerenciador de NPCs usando Citizens2 API
 * Sistema completo de criação, persistência e remoção de NPCs para ranking de clans
 */
@Getter
@Setter
public class NPCManager {
    
    private final Main plugin;
    private final File configFile;
    private YamlConfiguration npcConfig;
    private final Map<String, String> pendingWorldsForNPCs = new HashMap<>();
    private final Map<String, Integer> npcIds = new HashMap<>();
    private final HologramManager hologramManager;
    
    /**
     * Construtor do gerenciador de NPCs
     */
    public NPCManager(Main plugin) {
        this.plugin = plugin;
        this.configFile = new File(plugin.getDataFolder(), "tops.yml");
        this.hologramManager = new HologramManager(plugin);
        
        loadConfigs();
    }
    
    /**
     * Carrega as configurações
     */
    private void loadConfigs() {
        try {
            if (!plugin.getDataFolder().exists()) {
                plugin.getDataFolder().mkdirs();
            }
            
            
            if (!configFile.exists()) {
                plugin.saveResource("tops.yml", false);
            }
            npcConfig = YamlConfiguration.loadConfiguration(configFile);
            
            LogUtils.debug("Configurações carregadas com sucesso");
            
        } catch (Exception e) {
            LogUtils.severe("Erro ao carregar configurações: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    /**
     * Salva configuração no arquivo tops.yml
     */
    private void saveConfig() {
        try {
            npcConfig.save(configFile);

        } catch (Exception e) {
            LogUtils.error("Erro ao salvar tops.yml: " + e.getMessage());
        }
    }
    
    /**
     * Cria um NPC na posição especificada
     */
    public boolean createNPC(String id, int position, Location location) {
        try {

            
            if (location.getWorld() == null) {
                LogUtils.error("Mundo não encontrado para criar NPC " + id);
                return false;
            }
            
            
            if (existsNPC(id)) {
                LogUtils.warn("NPC " + id + " já existe, removendo primeiro");
                removeNPC(id);
            }
            
            
            if (!waitForCitizens()) {
                LogUtils.error("Citizens2 não está disponível");
                return false;
            }
            
            
            boolean spawned = spawnNPC(id, position, location);
            
            if (spawned) {
                
                ConfigurationSection section = npcConfig.getConfigurationSection("npc_positions");
                if (section == null) {
                    section = npcConfig.createSection("npc_positions");
                }
                
                ConfigurationSection npcSection = section.createSection(id);
                npcSection.set("world", location.getWorld().getName());
                npcSection.set("x", location.getX());
                npcSection.set("y", location.getY());
                npcSection.set("z", location.getZ());
                npcSection.set("yaw", location.getYaw());
                npcSection.set("pitch", location.getPitch());
                npcSection.set("position", position);
                npcSection.set("created_by_command", true);
                
                saveConfig();
                return true;
            } else {
                LogUtils.error("Falha ao criar NPC " + id);
                return false;
            }
            
        } catch (Exception e) {
            LogUtils.error("Erro ao criar NPC " + id + ": " + e.getMessage());
            e.printStackTrace();
            return false;
        }
    }
    
    /**
     * Cria o NPC físico usando Citizens2
     */
    private boolean spawnNPC(String id, int position, Location location) {
        try {
            if (!CitizensAPI.hasImplementation()) {
                LogUtils.error("Citizens2 não implementado");
                return false;
            }
            
            NPCRegistry registry = CitizensAPI.getNPCRegistry();
            String npcName = "hLiga_" + id;
            
            
            List<NPC> npcsToRemove = new ArrayList<>();
            for (NPC existingNPC : registry) {
                String existingId = existingNPC.data().get("hliga_id");
                if (id.equals(existingId) || npcName.equals(existingNPC.getName())) {
                    npcsToRemove.add(existingNPC);
                }
            }
            
            
            for (NPC npcToRemove : npcsToRemove) {
                LogUtils.warn("NPC com ID " + id + " (nome: " + npcName + ") já existe, removendo");
                try {
                    npcToRemove.despawn();
                    npcToRemove.destroy();
                } catch (Exception e) {
                    LogUtils.debug("Erro ao remover NPC existente: " + e.getMessage());
                }
            }
            
            
            NPC npc = registry.createNPC(EntityType.PLAYER, "");
            if (npc == null) {
                LogUtils.error("Falha ao criar NPC");
                return false;
            }
            
            
            npc.data().set("hliga_id", id);
            npc.data().set("nameplate-visible", false);
            npc.data().set("always-use-name-hologram", false);
            npc.data().set("use-name-hologram", false);
            
            
            ensureNPCNonPersistent(npc);
            
            
            if (npc.spawn(location)) {
                
                npc.getEntity().setCustomName(null);
                npc.getEntity().setCustomNameVisible(false);
                
                
                try {
                    
                    for (Object trait : npc.getTraits()) {
                        String traitName = trait.getClass().getSimpleName();
                        if (traitName.contains("Hologram")) {
                            
                            try {
                                trait.getClass().getMethod("clear").invoke(trait);
                                LogUtils.debug("Trait de holograma " + traitName + " limpo");
                            } catch (Exception clearEx) {
                                
                            }
                        }
                    }
                } catch (Exception e) {
                    
                    LogUtils.debug("Limpeza de traits opcional concluída");
                }
                
                
                npcIds.put(id, npc.getId());
                
                LogUtils.debug("NPC criado SEM NOME: " + npcName + " (ID: " + npc.getId() + ")");
                
                
                applySkinToNPC(npc, id, position);
                
                
                Bukkit.getScheduler().runTaskLater(plugin, () -> {
                    applySkinToNPC(npc, id, position);
                    LogUtils.debug("Segunda aplicação de skin executada para garantir funcionamento");
                }, 3L);
                
                
                Bukkit.getScheduler().runTaskLater(plugin, () -> {
                    createHologram(id, position, npc.getStoredLocation());
                }, 5L);
                
                return true;
            } else {
                LogUtils.error("Falha ao spawnar NPC " + npcName);
                npc.destroy();
                return false;
            }
            
        } catch (Exception e) {
            LogUtils.error("Erro ao spawnar NPC: " + e.getMessage());
            e.printStackTrace();
            return false;
        }
    }
    
    /**
     * Obtém a skin para uma posição específica
     */
    private String getSkinForPosition(int position) {
        try {
            
            LogUtils.debug("Verificando skin para posição " + position);
            
            ClanPoints clanPoints = plugin.getSeasonManager().getClanAtPosition(position);
            
            
            if (clanPoints != null && clanPoints.getPoints() > 0 && 
                npcConfig.getBoolean("npc.skin.usar_skin_lider", true)) {
                
                
                String leader = plugin.getClansManager().getClanLeaderName(clanPoints.getClanTag());
                LogUtils.debug("Clã " + clanPoints.getClanTag() + " no top - tentando usar skin do líder: " + leader);
                
                if (leader != null && !leader.isEmpty() && !leader.equals("Nenhum")) {
                    LogUtils.debug("Usando skin do líder: " + leader);
                    return leader; 
                }
            }
            
            
            LogUtils.debug("Usando skin padrão com value/signature do tops.yml para posição " + position);
            return "DEFAULT_SKIN"; 
            
        } catch (Exception e) {
            LogUtils.debug("Erro ao obter skin para posição " + position + ": " + e.getMessage());
            return "DEFAULT_SKIN"; 
        }
    }
    
    /**
     * Cria holograma acima do NPC usando HolographicDisplays ou DecentHolograms
     */
    private void createHologram(String npcId, int position, Location location) {
        try {
            if (!hologramManager.hasHologramSupport()) {
                LogUtils.warn("Sistema de hologramas não disponível - instale HolographicDisplays ou DecentHolograms");
                return;
            }
            
            
            List<ClanPoints> topClans = plugin.getDatabaseManager().getAdapter().getTopClans(Math.max(position * 2, 10));
            ClanPoints clanPoints = null;
            
            
            List<ClanPoints> clansWithPoints = new ArrayList<>();
            for (ClanPoints clan : topClans) {
                if (clan != null && clan.getPoints() > 0) {
                    clansWithPoints.add(clan);
                    LogUtils.debug("Clan válido encontrado: " + clan.getClanTag() + " com " + clan.getPoints() + " pontos");
                }
            }
            
            LogUtils.debug("=== LIMPEZA DE CACHE ===");
            LogUtils.debug("Total clans no banco: " + topClans.size());
            LogUtils.debug("Clans com pontos > 0: " + clansWithPoints.size());
            
            
            if (position > 0 && position <= clansWithPoints.size()) {
                clanPoints = clansWithPoints.get(position - 1);
            }
            
            LogUtils.debug("=== DADOS DO HOLOGRAMA ===");
            LogUtils.debug("Posição: " + position);
            LogUtils.debug("Total clans com pontos: " + clansWithPoints.size());
            LogUtils.debug("ClanPoints encontrado: " + (clanPoints != null));
            
            
            String tag, lider, pontos;
            
            if (clanPoints == null || clanPoints.getPoints() <= 0) {
                
                tag = npcConfig.getString("holograma.vazio.tag", "&c&lNenhum Clan");
                lider = npcConfig.getString("holograma.vazio.lider", "&7[Aguardando]");
                pontos = npcConfig.getString("holograma.vazio.pontos", "0");
                
                LogUtils.debug("Usando configurações vazio - Clan: " + (clanPoints != null ? clanPoints.getClanTag() + " com " + clanPoints.getPoints() + " pontos" : "null"));
            } else {
                
                String coloredTag = plugin.getClansManager().getColoredClanTag(clanPoints.getClanTag());
                tag = coloredTag != null && !coloredTag.isEmpty() ? coloredTag : clanPoints.getClanTag();
                
                
                pontos = NumberFormatter.format(clanPoints.getPoints());
                
                
                lider = findClanLeader(clanPoints.getClanTag(), "&7[Sem Líder]");
                
                LogUtils.debug("Tag original: " + clanPoints.getClanTag());
                LogUtils.debug("Tag colorida: " + tag);
                LogUtils.debug("Líder: " + lider);
                LogUtils.debug("Pontos formatados: " + pontos);
            }
            
            
            List<String> templateLines = npcConfig.getStringList("holograma.linhas");
            if (templateLines.isEmpty()) {
                templateLines.add("&6Top #{posicao}");
                templateLines.add("{tag} - &e{pontos} pontos");
                templateLines.add("&fLíder: &e{lider}");
            }
            
            
            List<String> processedLines = new ArrayList<>();
            for (String line : templateLines) {
                String processedLine = line
                    .replace("{posicao}", String.valueOf(position))
                    .replace("{tag}", tag)
                    .replace("{lider}", lider)
                    .replace("{pontos}", pontos);
                
                processedLines.add(ChatColor.translateAlternateColorCodes('&', processedLine));
            }
            
            
            Location hologramLocation = location.clone().add(0, 3.2, 0);
            
            
            boolean created = hologramManager.createHologram("hliga_" + npcId, hologramLocation, processedLines);
            
            if (created) {
                LogUtils.debug("Holograma criado para NPC " + npcId + " na posição " + position + " usando " + hologramManager.getProvider());
            } else {
                LogUtils.error("Falha ao criar holograma para NPC " + npcId);
            }
            
        } catch (Exception e) {
            LogUtils.error("Erro ao criar holograma: " + e.getMessage());
        }
    }
    
    /**
     * Remove holograma usando o sistema profissional
     */
    private void removeHologram(String npcId) {
        try {
            hologramManager.removeHologram("hliga_" + npcId);
            LogUtils.debug("Holograma removido para NPC: " + npcId);
        } catch (Exception e) {
            LogUtils.error("Erro ao remover holograma: " + e.getMessage());
        }
    }
    
    /**
     * Atualiza holograma para valores padrão (quando não há dados de ranking)
     * CORRIGIDO: Agora respeita completamente a configuração do tops.yml
     */
    private void updateHologramToDefault(String npcId) {
        try {
            
            ConfigurationSection section = npcConfig.getConfigurationSection("npc_positions." + npcId);
            if (section == null) {
                return;
            }
            
            int position = section.getInt("position", 1);
            String worldName = section.getString("world");
            double x = section.getDouble("x");
            double y = section.getDouble("y");
            double z = section.getDouble("z");
            
            Location location = new Location(Bukkit.getWorld(worldName), x, y, z);
            
            
            String defaultTag = npcConfig.getString("holograma.vazio.tag", "&c&lNenhum Clan");
            String defaultLider = npcConfig.getString("holograma.vazio.lider", "&7[Aguardando]");
            String defaultPontos = npcConfig.getString("holograma.vazio.pontos", "0");
            
            
            List<String> templateLines = npcConfig.getStringList("holograma.linhas");
            if (templateLines.isEmpty()) {
                
                templateLines.add("&6Top #{posicao}");
                templateLines.add("{tag} - &e{pontos} pontos");
                templateLines.add("&fLíder: &e{lider}");
            }
            
            
            List<String> processedLines = new ArrayList<>();
            for (String line : templateLines) {
                String processedLine = line
                    .replace("{posicao}", String.valueOf(position))
                    .replace("{tag}", defaultTag)
                    .replace("{lider}", defaultLider)
                    .replace("{pontos}", defaultPontos);
                
                processedLines.add(ChatColor.translateAlternateColorCodes('&', processedLine));
            }
            
            
            Location hologramLocation = location.clone().add(0, 3.2, 0);
            
            
            hologramManager.removeHologram("hliga_" + npcId);
            boolean created = hologramManager.createHologram("hliga_" + npcId, hologramLocation, processedLines);
            
            if (created) {
                LogUtils.debug("Holograma padrão criado para NPC " + npcId + " na posição " + position + " usando configuração do tops.yml");
            } else {
                LogUtils.error("Falha ao criar holograma padrão para NPC " + npcId);
            }
            
        } catch (Exception e) {
            LogUtils.error("Erro ao atualizar holograma para padrão: " + e.getMessage());
        }
    }
    
    /**
     * Atualiza a skin do NPC baseada nos dados atuais
     */
    private void updateNPCSkin(String id, int position) {
        try {
            if (!CitizensAPI.hasImplementation()) {
                return;
            }
            
            Integer citizensId = npcIds.get(id);
            if (citizensId == null) {
                return;
            }
            
            NPCRegistry registry = CitizensAPI.getNPCRegistry();
            NPC npc = registry.getById(citizensId);
            if (npc == null) {
                return;
            }
            
            
            applySkinToNPC(npc, id, position);
            
            
            ensureNPCNonPersistent(npc);
            
        } catch (Exception e) {
            LogUtils.debug("Erro ao atualizar skin do NPC " + id + ": " + e.getMessage());
        }
    }
    
    /**
     * Reseta todos os NPCs para skin padrão (quando temporada finaliza ou não há dados)
     */
    public void resetAllNPCsToDefault() {
        try {
            LogUtils.info("Resetando todos os NPCs para skin padrão...");
            
            ConfigurationSection section = npcConfig.getConfigurationSection("npc_positions");
            if (section == null) {
                LogUtils.debug("Nenhum NPC configurado para resetar");
                return;
            }
            
            int reset = 0;
            for (String id : section.getKeys(false)) {
                if (resetNPCToDefault(id)) {
                    reset++;
                }
            }
            
            LogUtils.info("Resetados " + reset + " NPCs para skin padrão");
            
        } catch (Exception e) {
            LogUtils.error("Erro ao resetar NPCs para padrão: " + e.getMessage());
        }
    }
    
    /**
     * Reseta um NPC específico para skin padrão
     */
    private boolean resetNPCToDefault(String id) {
        try {
            if (!CitizensAPI.hasImplementation()) {
                return false;
            }
            
            Integer citizensId = npcIds.get(id);
            if (citizensId == null) {
                return false;
            }
            
            NPCRegistry registry = CitizensAPI.getNPCRegistry();
            NPC npc = registry.getById(citizensId);
            if (npc == null) {
                return false;
            }
            
            
            String defaultPlayerName = npcConfig.getString("npc.skin.jogador_padrao", "MHF_Question");
            LogUtils.debug("Resetando NPC " + id + " para skin padrão: " + defaultPlayerName);
            applySkinWithPlayerName(npc, id, defaultPlayerName);
            
            
            updateHologramToDefault(id);
            
            return true;
            
        } catch (Exception e) {
            LogUtils.debug("Erro ao resetar NPC " + id + " para padrão: " + e.getMessage());
            return false;
        }
    }
    
    /**
     * MÉTODO CRÍTICO - Garante que NPC seja não-persistente
     * Este método deve ser chamado SEMPRE que um NPC é criado ou modificado
     */
    private void ensureNPCNonPersistent(NPC npc) {
        try {
            
            try {
                npc.getClass().getMethod("setPersistent", boolean.class).invoke(npc, false);
                LogUtils.debug("✅ NPC configurado como NÃO PERSISTENTE via setPersistent(false)");
            } catch (Exception e) {
                LogUtils.debug("setPersistent() não disponível nesta versão do Citizens2");
            }
            
            
            if (npc.getEntity() != null) {
                npc.getEntity().setCustomName(null);
                npc.getEntity().setCustomNameVisible(false);
            }
            
            
            npc.setName("");
            
            
            npc.data().set("should-save", false);
            npc.data().set("persist", false);
            npc.data().set("temporary", true);
            npc.data().set("save", false);
            npc.data().set("saveable", false);
            
            
            try {
                npc.getClass().getMethod("setTemporary", boolean.class).invoke(npc, true);
            } catch (Exception ignored) {
                
            }
            
            
            try {
                Object saveData = npc.getClass().getMethod("getSaveData").invoke(npc);
                if (saveData != null) {
                    saveData.getClass().getMethod("clear").invoke(saveData);
                }
            } catch (Exception ignored) {
                
            }
            
            LogUtils.debug("✅ NPC configurado como TEMPORÁRIO e NÃO-PERSISTENTE - não será salvo");
            
        } catch (Exception e) {
            LogUtils.debug("Erro ao configurar NPC como não-persistente: " + e.getMessage());
        }
    }
    
    /**
     * MÉTODO UNIFICADO - Aplica skin ao NPC usando APENAS nomes de jogadores
     */
    private void applySkinToNPC(NPC npc, String npcId, int position) {
        try {
            String playerName = getPlayerNameForPosition(position);
            
            LogUtils.debug("Determinando skin para NPC " + npcId + " posição " + position + ": " + playerName);
            
            
            if (playerName != null && !playerName.isEmpty() && !"Nenhum".equals(playerName)) {
                
                LogUtils.debug("→ Usando skin do jogador: " + playerName);
                applySkinWithPlayerName(npc, npcId, playerName);
            } else {
                
                String defaultPlayerName = npcConfig.getString("npc.skin.jogador_padrao", "Steve");
                LogUtils.debug("→ Usando skin padrão do jogador: " + defaultPlayerName);
                applySkinWithPlayerName(npc, npcId, defaultPlayerName);
            }
            
        } catch (Exception e) {
            LogUtils.debug("Erro ao aplicar skin ao NPC " + npcId + ": " + e.getMessage());
            
            LogUtils.debug("→ Fallback: usando skin do Steve");
            applySkinWithPlayerName(npc, npcId, "Steve");
        }
    }
    
    /**
     * Obtém o nome do jogador para uma posição específica
     */
    private String getPlayerNameForPosition(int position) {
        try {
            LogUtils.debug("Verificando jogador para posição " + position);
            
            ClanPoints clanPoints = plugin.getSeasonManager().getClanAtPosition(position);
            
            
            if (clanPoints != null && clanPoints.getPoints() > 0) {
                
                String leader = plugin.getClansManager().getClanLeaderName(clanPoints.getClanTag());
                LogUtils.debug("Clã " + clanPoints.getClanTag() + " no top - líder: " + leader);
                
                if (leader != null && !leader.isEmpty() && !leader.equals("Nenhum")) {
                    LogUtils.debug("Retornando líder: " + leader);
                    return leader;
                }
            }
            
            
            LogUtils.debug("Sem líder válido - usará jogador padrão");
            return null;
            
        } catch (Exception e) {
            LogUtils.debug("Erro ao obter jogador para posição " + position + ": " + e.getMessage());
            return null;
        }
    }
    
    /**
     * MÉTODO CRÍTICO - Aplica skin usando VALUE e SIGNATURE de forma EXCLUSIVAMENTE TRANSITÓRIA
     * Este método implementa a aplicação de skin temporária sem qualquer persistência automática
     */
    private void applySkinWithValueSignature(NPC npc, String npcId) {
        try {
            String skinValue = npcConfig.getString("npc.skin.skin_padrao.value", "");
            String skinSignature = npcConfig.getString("npc.skin.skin_padrao.signature", "");
            
            if (skinValue.isEmpty() || skinSignature.isEmpty()) {
                LogUtils.warning("Value ou signature vazios no tops.yml - não é possível aplicar skin customizada");
                return;
            }
            
            LogUtils.debug("Aplicando skin VALUE/SIGNATURE transitória ao NPC " + npcId);
            LogUtils.debug("Value: " + (skinValue.length() > 50 ? "CONFIGURADO (" + skinValue.length() + " chars)" : skinValue));
            LogUtils.debug("Signature: " + (skinSignature.length() > 50 ? "CONFIGURADO (" + skinSignature.length() + " chars)" : skinSignature));
            
            
            Class<?> skinTraitClass = Class.forName("net.citizensnpcs.trait.SkinTrait");
            Object skinTrait = npc.getOrAddTrait(skinTraitClass.asSubclass(net.citizensnpcs.api.trait.Trait.class));
            
            if (skinTrait == null) {
                LogUtils.warning("Falha ao obter SkinTrait para NPC " + npcId);
                return;
            }
            
            LogUtils.debug("✓ SkinTrait obtido com sucesso para NPC " + npcId);
            
            
            boolean skinApplied = false;
            
            
            String[] methodsToTry = {
                "setTexture",           
                "setSkin",              
                "setSkinPersistent",    
                "setPlayerSkin"         
            };
            
            for (String methodName : methodsToTry) {
                if (skinApplied) break;
                
                try {
                    if ("setSkinPersistent".equals(methodName)) {
                        
                        java.lang.reflect.Method method = skinTrait.getClass().getMethod(methodName, String.class, String.class, String.class);
                        method.invoke(skinTrait, "", skinValue, skinSignature);
                        LogUtils.debug("✅ " + methodName + "(empty, VALUE, SIGNATURE) executado");
                        skinApplied = true;
                    } else {
                        
                        java.lang.reflect.Method method = skinTrait.getClass().getMethod(methodName, String.class, String.class);
                        method.invoke(skinTrait, skinValue, skinSignature);
                        LogUtils.debug("✅ " + methodName + "(VALUE, SIGNATURE) executado");
                        skinApplied = true;
                    }
                } catch (Exception e) {
                    LogUtils.debug("✗ " + methodName + "() não disponível: " + e.getMessage());
                }
            }
            
            
            if (!skinApplied) {
                try {
                    java.lang.reflect.Method setSkinMethod = skinTrait.getClass().getMethod("setSkin", String.class);
                    setSkinMethod.invoke(skinTrait, skinValue); 
                    LogUtils.debug("✅ setSkin(VALUE) executado como último recurso");
                    skinApplied = true;
                } catch (Exception e) {
                    LogUtils.debug("✗ setSkin(VALUE) também falhou: " + e.getMessage());
                }
            }
            
            if (!skinApplied) {
                LogUtils.warning("❌ Falha ao aplicar skin VALUE/SIGNATURE ao NPC " + npcId);
                return;
            }
            
            
            boolean visualUpdated = false;
            
            
            try {
                java.lang.reflect.Method fetchSkinMethod = skinTrait.getClass().getMethod("fetchSkin");
                fetchSkinMethod.invoke(skinTrait);
                LogUtils.debug("✅ fetchSkin() executado");
                visualUpdated = true;
            } catch (Exception e) {
                LogUtils.debug("fetchSkin() não disponível: " + e.getMessage());
            }
            
            
            if (npc.isSpawned() && npc.getEntity() != null) {
                Location location = npc.getEntity().getLocation();
                npc.despawn();
                
                
                org.bukkit.Bukkit.getScheduler().runTaskLater(plugin, () -> {
                    if (npc.spawn(location)) {
                        ensureNPCNonPersistent(npc);
                        LogUtils.debug("✅ NPC respawnado para garantir skin VALUE/SIGNATURE visível");
                        
                        
                        try {
                            Object newSkinTrait = npc.getOrAddTrait(skinTrait.getClass().asSubclass(net.citizensnpcs.api.trait.Trait.class));
                            java.lang.reflect.Method fetchMethod = newSkinTrait.getClass().getMethod("fetchSkin");
                            fetchMethod.invoke(newSkinTrait);
                            LogUtils.debug("✅ fetchSkin() executado após respawn");
                        } catch (Exception ignored) {
                            
                        }
                    }
                }, 2L);
            }
            
            
            try {
                
                java.lang.reflect.Method setSkinPersistentMethod = skinTrait.getClass().getMethod("setSkinPersistent", boolean.class);
                setSkinPersistentMethod.invoke(skinTrait, false);
                LogUtils.debug("✓ Persistência de skin desabilitada explicitamente");
            } catch (Exception e) {
                LogUtils.debug("setSkinPersistent(boolean) não disponível - prosseguindo sem persistência");
            }
            
            
            ensureNPCNonPersistent(npc);
            
            LogUtils.info("✅ Skin VALUE/SIGNATURE aplicada de forma TRANSITÓRIA ao NPC " + npcId);
            LogUtils.debug("→ Skin aplicada apenas em runtime, sem persistência no Citizens");
            
        } catch (Exception e) {
            LogUtils.error("Erro crítico ao aplicar skin com value/signature: " + e.getMessage());
        }
    }
    
    /**
     * Aplica skin de jogador ao NPC - Sistema simplificado por nome
     */
    private void applySkinWithPlayerName(NPC npc, String npcId, String playerName) {
        try {
            LogUtils.debug("Aplicando skin do jogador " + playerName + " ao NPC " + npcId);
            
            
            Class<?> skinTraitClass = Class.forName("net.citizensnpcs.trait.SkinTrait");
            Object skinTrait = npc.getOrAddTrait(skinTraitClass.asSubclass(net.citizensnpcs.api.trait.Trait.class));
            
            if (skinTrait == null) {
                LogUtils.warning("Falha ao obter SkinTrait para NPC " + npcId);
                return;
            }
            
            
            boolean skinApplied = false;
            
            
            try {
                java.lang.reflect.Method setSkinNameMethod = skinTrait.getClass().getMethod("setSkinName", String.class);
                setSkinNameMethod.invoke(skinTrait, playerName);
                LogUtils.debug("✅ setSkinName(" + playerName + ") aplicado");
                skinApplied = true;
            } catch (Exception e) {
                
                try {
                    java.lang.reflect.Method setSkinMethod = skinTrait.getClass().getMethod("setSkin", String.class);
                    setSkinMethod.invoke(skinTrait, playerName);
                    LogUtils.debug("✅ setSkin(" + playerName + ") aplicado");
                    skinApplied = true;
                } catch (Exception e2) {
                    LogUtils.warning("Não foi possível aplicar skin por nome: " + e2.getMessage());
                }
            }
            
            if (!skinApplied) {
                LogUtils.warning("❌ Falha ao aplicar skin do jogador " + playerName + " ao NPC " + npcId);
                return;
            }
            
            
            try {
                java.lang.reflect.Method fetchSkinMethod = skinTrait.getClass().getMethod("fetchSkin");
                fetchSkinMethod.invoke(skinTrait);
                LogUtils.debug("✅ fetchSkin() executado para " + playerName);
            } catch (Exception e) {
                LogUtils.debug("fetchSkin() não disponível: " + e.getMessage());
            }
            
            LogUtils.debug("✅ Skin do jogador " + playerName + " aplicada ao NPC " + npcId);
            
        } catch (Exception e) {
            LogUtils.error("Erro ao aplicar skin do jogador " + playerName + ": " + e.getMessage());
        }
    }
    
    /**
     * Atualiza holograma existente com novos dados
     */
    private void updateHologram(String npcId, int position) {
        try {
            if (!hologramManager.hasHologramSupport()) {
                return;
            }
            
            
            List<ClanPoints> freshTopClans = plugin.getDatabaseManager().getAdapter().getTopClans(position + 2);
            ClanPoints clanPoints = null;
            
            
            List<ClanPoints> validClans = new ArrayList<>();
            for (ClanPoints clan : freshTopClans) {
                if (clan != null && clan.getPoints() > 0) {
                    validClans.add(clan);
                }
            }
            
            
            if (position > 0 && position <= validClans.size()) {
                clanPoints = validClans.get(position - 1);
                LogUtils.debug("FRESH DATA: Posição " + position + " = " + clanPoints.getClanTag() + " com " + clanPoints.getPoints() + " pontos");
            }
            
            
            String tagVazia = npcConfig.getString("holograma.vazio.tag", "&cNenhum Clan");
            String liderVazio = npcConfig.getString("holograma.vazio.lider", "&7[Nenhum]");
            String pontosVazios = npcConfig.getString("holograma.vazio.pontos", "0");
            
            String tag = tagVazia;
            String lider = liderVazio;
            String pontos = pontosVazios;
            
            if (clanPoints != null) {
                
                String clanTag = clanPoints.getClanTag();
                tag = plugin.getClansManager().getColoredClanTag(clanTag);
                
                
                lider = findClanLeader(clanTag, liderVazio);
                
                pontos = NumberFormatter.format(clanPoints.getPoints());
                LogUtils.debug("DADOS ATUALIZADOS - Tag: " + tag + ", Líder: " + lider + ", Pontos: " + pontos);
            }
            
            
            List<String> templateLines = npcConfig.getStringList("holograma.linhas");
            if (templateLines.isEmpty()) {
                templateLines.add("&6Top #{posicao}");
                templateLines.add("{tag} - &e{pontos} pontos");
                templateLines.add("&fLíder: &e{lider}");
            }
            
            
            List<String> processedLines = new ArrayList<>();
            for (String line : templateLines) {
                String processedLine = line
                    .replace("{posicao}", String.valueOf(position))
                    .replace("{tag}", tag)
                    .replace("{lider}", lider)
                    .replace("{pontos}", pontos);
                
                processedLines.add(ChatColor.translateAlternateColorCodes('&', processedLine));
            }
            
            
            boolean updated = hologramManager.updateHologram("hliga_" + npcId, processedLines);
            
            if (updated) {
                LogUtils.debug("Holograma atualizado para NPC " + npcId + " na posição " + position);
            }
            
        } catch (Exception e) {
            LogUtils.error("Erro ao atualizar holograma: " + e.getMessage());
        }
    }
    

    
    /**
     * Remove um NPC
     */
    public boolean removeNPC(String id) {
        try {
            
            removeHologram(id);
            
            
            boolean removed = removePhysicalNPC(id);
            
            
            ConfigurationSection section = npcConfig.getConfigurationSection("npc_positions");
            if (section != null && section.contains(id)) {
                section.set(id, null);
                saveConfig();
                LogUtils.debug("NPC " + id + " removido do arquivo");
            }
            
            return removed;
            
        } catch (Exception e) {
            LogUtils.error("Erro ao remover NPC " + id + ": " + e.getMessage());
            return false;
        }
    }
    
    /**
     * Remove apenas a entidade física de um NPC
     */
    private boolean removePhysicalNPC(String id) {
        try {
            if (!CitizensAPI.hasImplementation()) {
                LogUtils.warn("Citizens2 não disponível para remoção de NPC");
                return false;
            }
            
            NPCRegistry registry = CitizensAPI.getNPCRegistry();
            String npcName = "hLiga_" + id;
            List<NPC> toRemove = new ArrayList<>();
            
            
            if (npcIds.containsKey(id)) {
                Integer citizensId = npcIds.get(id);
                NPC npc = registry.getById(citizensId);
                if (npc != null && !toRemove.contains(npc)) {
                    toRemove.add(npc);
                }
            }
            
            
            for (NPC npc : registry) {
                String hligaId = npc.data().get("hliga_id");
                if (id.equals(hligaId) || npcName.equals(npc.getName())) {
                    if (!toRemove.contains(npc)) {
                        toRemove.add(npc);
                    }
                }
            }
            
            
            if (!toRemove.isEmpty()) {
                for (NPC npc : toRemove) {
                    try {
                        npc.despawn();
                        npc.destroy();
                    } catch (Exception e) {
                        LogUtils.debug("Erro ao despawnar/destruir NPC " + id + ": " + e.getMessage());
                    }
                }
                
                
                removeHologram(id);
                npcIds.remove(id);
                
                LogUtils.debug("NPC físico " + id + " e seus hologramas foram removidos com sucesso. Total removido: " + toRemove.size());
                return true;
            }
            
            LogUtils.warn("NPC físico " + id + " não encontrado para remoção");
            return false;
            
        } catch (Exception e) {
            LogUtils.error("Erro ao remover NPC físico " + id + ": " + e.getMessage());
            return false;
        }
    }
    
    /**
     * Verifica se um NPC existe
     */
    public boolean existsNPC(String id) {
        ConfigurationSection section = npcConfig.getConfigurationSection("npc_positions");
        return section != null && section.contains(id);
    }
    
    /**
     * Verifica se um NPC existe (alias para compatibilidade)
     */
    public boolean npcExists(String id) {
        return existsNPC(id);
    }
    
    /**
     * Cria NPC a partir de comando - MÉTODO CORRIGIDO
     * Garante que a skin seja aplicada corretamente e NPC seja não-persistente
     */
    public boolean createNPCFromCommand(String id, int position, Location location) {
        try {
            
            boolean success = createNPC(id, position, location);
            
            if (success) {
                LogUtils.debug("✓ NPC " + id + " criado via comando com sucesso");
                
                
                org.bukkit.Bukkit.getScheduler().runTaskLater(plugin, () -> {
                    Integer citizensId = npcIds.get(id);
                    if (citizensId != null) {
                        NPCRegistry registry = CitizensAPI.getNPCRegistry();
                        NPC npc = registry.getById(citizensId);
                        if (npc != null) {
                            
                            applySkinToNPC(npc, id, position);
                            
                            ensureNPCNonPersistent(npc);
                            LogUtils.debug("✓ Skin aplicada ao NPC " + id + " criado via comando");
                        }
                    }
                }, 1L);
            }
            
            return success;
        } catch (Exception e) {
            LogUtils.error("Erro ao criar NPC via comando: " + e.getMessage());
            return false;
        }
    }
    
    /**
     * Atualiza um NPC específico
     */
    public boolean updateNPC(String id) {
        try {
            ConfigurationSection section = npcConfig.getConfigurationSection("npc_positions." + id);
            if (section == null) {
                return false;
            }
            
            int position = section.getInt("position", 1);
            
            
            updateNPCSkin(id, position);
            
            
            updateHologram(id, position);
            
            LogUtils.debug("NPC " + id + " atualizado (skin + holograma)");
            return true;
        } catch (Exception e) {
            LogUtils.error("Erro ao atualizar NPC " + id + ": " + e.getMessage());
            return false;
        }
    }
    
    /**
     * Atualiza todos os NPCs
     */
    public int updateAllNPCs() {
        int updated = 0;
        try {
            ConfigurationSection section = npcConfig.getConfigurationSection("npc_positions");
            if (section == null) {
                return 0;
            }
            
            for (String id : section.getKeys(false)) {
                if (updateNPC(id)) {
                    updated++;
                }
            }
            
            LogUtils.debug("Atualizados " + updated + " NPCs");
            return updated;
        } catch (Exception e) {
            LogUtils.error("Erro ao atualizar NPCs: " + e.getMessage());
            return updated;
        }
    }
    
    /**
     * Recria todos os NPCs salvos no arquivo tops.yml
     * REGRA: Só recria se tiver dados no arquivo - não usa memória
     * Chamado automaticamente no startup do plugin
     */
    public void recreateNPCsFromFile() {
        try {
            LogUtils.debug("🔍 Buscando NPCs salvos no arquivo tops.yml...");
            
            if (!waitForCitizens()) {
                LogUtils.warn("Citizens2 não disponível - aguardando...");
                return;
            }
            
            
            if (!configFile.exists()) {
                LogUtils.debug("📄 Arquivo tops.yml não existe - nenhum NPC para recriar");
                return;
            }
            
            ConfigurationSection section = npcConfig.getConfigurationSection("npc_positions");
            if (section == null || section.getKeys(false).isEmpty()) {
                LogUtils.debug("📝 Arquivo existe mas não tem dados de NPCs salvos");
                return;
            }
            
            int recreated = 0;
            for (String npcId : section.getKeys(false)) {
                try {
                    ConfigurationSection npcSection = section.getConfigurationSection(npcId);
                    if (npcSection == null) continue;
                    
                    
                    String worldName = npcSection.getString("world");
                    double x = npcSection.getDouble("x");
                    double y = npcSection.getDouble("y");
                    double z = npcSection.getDouble("z");
                    float yaw = (float) npcSection.getDouble("yaw");
                    float pitch = (float) npcSection.getDouble("pitch");
                    int position = npcSection.getInt("position");
                    
                    
                    World world = Bukkit.getWorld(worldName);
                    if (world == null) {
                        LogUtils.warn("Mundo " + worldName + " não encontrado para NPC " + npcId);
                        continue;
                    }
                    
                    
                    Location location = new Location(world, x, y, z, yaw, pitch);
                    
                    
                    if (spawnNPC(npcId, position, location)) {
                        recreated++;
                        LogUtils.debug("NPC " + npcId + " recriado na posição " + position);
                    } else {
                        LogUtils.warn("Falha ao recriar NPC " + npcId);
                    }
                    
                } catch (Exception e) {
                    LogUtils.error("Erro ao recriar NPC " + npcId + ": " + e.getMessage());
                }
            }
            
            if (recreated > 0) {
                LogUtils.debug("✅ " + recreated + " NPCs recriados com sucesso a partir do arquivo!");
            } else {
                LogUtils.debug("⚠️ Nenhum NPC foi recriado (dados existem mas falharam)");
            }
            
        } catch (Exception e) {
            LogUtils.error("Erro durante recriação de NPCs: " + e.getMessage());
        }
    }
    
    /**
     * Aguarda Citizens2 estar disponível
     */
    private boolean waitForCitizens() {
        int attempts = 0;
        while (attempts < 10 && !CitizensAPI.hasImplementation()) {
            try {
                Thread.sleep(100);
                attempts++;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return CitizensAPI.hasImplementation();
    }
    
    /**
     * Remove todos os NPCs (alias para compatibilidade)
     */
    public void removeAllNPCs() {
        cleanup();
    }
    
    /**
     * Limpeza forçada de todos os dados (alias para compatibilidade)
     */
    public void forceCleanupAllData() {
        cleanup();
    }
    
    /**
     * Cleanup ao desligar servidor - remove NPCs físicos, PRESERVA localizações
     * REGRA: Só destrói NPCs físicos, mantém arquivo para próximo restart
     */
    public void cleanup() {
        try {
            LogUtils.debug("🧹 Iniciando limpeza de NPCs físicos ao desligar servidor...");
            
            
            hologramManager.removeAllHolograms();
            LogUtils.debug("🔹 Hologramas removidos");
            
            
            removeAllPhysicalNPCs();
            LogUtils.debug("🔹 NPCs físicos removidos");
            
            
            
            LogUtils.debug("✅ Limpeza concluída - localizações preservadas no tops.yml para próximo restart");
            
            LogUtils.debug("Limpeza completa finalizada");
            
        } catch (Exception e) {
            LogUtils.severe("Erro durante limpeza completa: " + e.getMessage());
        }
    }
    
    /**
     * Limpeza TOTAL - usado quando plugin é removido
     * Remove NPCs físicos E dados de localização
     */
    public void cleanupOnPluginRemoval() {
        try {
            LogUtils.debug("🗑️ REMOÇÃO COMPLETA - Plugin sendo removido do servidor");
            
            
            hologramManager.removeAllHolograms();
            
            
            removeAllPhysicalNPCs();
            
            
            ConfigurationSection section = npcConfig.getConfigurationSection("npc_positions");
            if (section != null) {
                npcConfig.set("npc_positions", null);
                saveConfig();
                LogUtils.debug("🔹 Dados de localização removidos do tops.yml");
            }
            
            
            LogUtils.debug("✅ Plugin removido - NPCs e dados limpos, configurações preservadas");
            
        } catch (Exception e) {
            LogUtils.error("Erro durante remoção completa: " + e.getMessage());
        }
    }
    
    /**
     * Força a limpeza de todos os NPCs do Citizens2 (método agressivo)
     */
    public void forceCleanCitizensNPCs() {
        try {
            if (!CitizensAPI.hasImplementation()) {
                return;
            }
            
            LogUtils.debug("🔧 Forçando limpeza completa dos NPCs do Citizens2...");
            
            NPCRegistry registry = CitizensAPI.getNPCRegistry();
            List<NPC> npcsToRemove = new ArrayList<>();
            
            
            for (NPC npc : registry) {
                if (npc.data().has("hliga_id") || npc.getName().startsWith("hLiga_")) {
                    npcsToRemove.add(npc);
                }
            }
            
            
            for (NPC npc : npcsToRemove) {
                try {
                    
                    if (npc.isSpawned()) {
                        npc.despawn();
                    }
                    
                    
                    try {
                        
                        npc.data().set("should-save", null);
                        npc.data().set("persist", null);
                        npc.data().set("temporary", null);
                        npc.data().set("save", null);
                        npc.data().set("saveable", null);
                    } catch (Exception e) {
                        LogUtils.debug("Erro ao limpar dados do NPC: " + e.getMessage());
                    }
                    
                    
                    npc.destroy();
                    
                    LogUtils.debug("NPC " + npc.getName() + " forçadamente removido do Citizens2");
                    
                } catch (Exception e) {
                    LogUtils.debug("Erro ao remover NPC " + npc.getName() + ": " + e.getMessage());
                }
            }
            
            
            npcIds.clear();
            
            LogUtils.debug("✅ Limpeza forçada do Citizens2 concluída - " + npcsToRemove.size() + " NPCs removidos");
            
        } catch (Exception e) {
            LogUtils.error("Erro durante limpeza forçada: " + e.getMessage());
        }
    }
    
    /**
     * Remove todos os NPCs físicos
     */
    private void removeAllPhysicalNPCs() {
        try {
            if (!CitizensAPI.hasImplementation()) {
                LogUtils.warn("Citizens2 não disponível para limpeza");
                return;
            }
            
            NPCRegistry registry = CitizensAPI.getNPCRegistry();
            List<NPC> toRemove = new ArrayList<>();
            
            for (NPC npc : registry) {
                if (npc.data().has("hliga_id") || npc.getName().startsWith("hLiga_")) {
                    toRemove.add(npc);
                }
            }
            
            for (NPC npc : toRemove) {
                try {
                    LogUtils.debug("Removendo NPC: " + npc.getName() + " (ID: " + npc.getId() + ")");
                    if (npc.isSpawned()) {
                        npc.despawn(); 
                    }
                    npc.destroy(); 
                    LogUtils.debug("NPC destruído: " + npc.getName());
                } catch (Exception e) {
                    LogUtils.error("Erro ao remover NPC " + npc.getName() + ": " + e.getMessage());
                }
            }
            
            
            npcIds.clear();
            
            if (toRemove.size() > 0) {
                LogUtils.debug("Todos os NPCs do plugin removidos: " + toRemove.size() + " NPCs destruídos");
            }
            
        } catch (Exception e) {
            LogUtils.error("Erro ao remover NPCs físicos: " + e.getMessage());
        }
    }
    

    
    /**
     * Obtém todos os IDs de NPCs
     */
    public Set<String> getAllNPCIds() {
        ConfigurationSection section = npcConfig.getConfigurationSection("npc_positions");
        if (section == null) {
            return new java.util.HashSet<>();
        }
        return section.getKeys(false);
    }
    

    
    /**
     * Extrai a posição do ID
     */
    private int extractPositionFromId(String id) {
        try {
            int position = Integer.parseInt(id.replaceAll("[^0-9]", ""));
            
            return position <= 0 ? 1 : position;
        } catch (NumberFormatException e) {
            return 1;
        }
    }
    
    /**
     * Método unificado para buscar o líder de um clã
     * Garante que startup e update usem a mesma lógica
     */
    private String findClanLeader(String clanTag, String defaultValue) {
        String lider = defaultValue;
        
        try {
            
            String leaderFromSystem = plugin.getClansManager().getClanLeaderName(clanTag);
            
            if (leaderFromSystem != null && !leaderFromSystem.trim().isEmpty()) {
                lider = "&e" + leaderFromSystem;
                return lider;
            }
            
            
            GenericClan clan = plugin.getClansManager().getClanByTag(clanTag);
            if (clan != null && clan.getLeaderName() != null && !clan.getLeaderName().trim().isEmpty()) {
                lider = "&e" + clan.getLeaderName();
                return lider;
            }
            
            
            List<Player> onlineMembers = plugin.getClansManager().getOnlineClanMembers(clanTag);
            
            for (Player member : onlineMembers) {
                if (plugin.getClansManager().isPlayerLeader(member)) {
                    lider = "&e" + member.getName();
                    return lider;
                }
            }
            
        } catch (Exception e) {
            LogUtils.warning("Erro ao buscar líder do clan " + clanTag + ": " + e.getMessage());
            lider = "&7[Erro ao carregar]";
        }
        
        return lider;
    }
    
    /**
     * Reset completo de todos os NPCs para skin padrão quando temporada finaliza
     */
    public void resetNPCsToDefaultSkin() {
        try {
            LogUtils.info("Iniciando reset de NPCs para skin padrão...");
            
            ConfigurationSection section = npcConfig.getConfigurationSection("npc_positions");
            if (section == null) {
                LogUtils.debug("Nenhum NPC configurado para resetar");
                return;
            }
            
            
            String defaultSkinValue = npcConfig.getString("skin_vazio.value", "");
            String defaultSkinSignature = npcConfig.getString("skin_vazio.signature", "");
            
            if (defaultSkinValue.isEmpty()) {
                LogUtils.warning("Skin padrão não configurada em tops.yml - usando skin padrão do Minecraft");
            }
            
            int reseted = 0;
            for (String npcId : section.getKeys(false)) {
                try {
                    
                    updateHologramToEmpty(npcId);
                    
                    
                    if (resetNPCSkinToDefault(npcId, defaultSkinValue, defaultSkinSignature)) {
                        reseted++;
                    }
                    
                } catch (Exception e) {
                    LogUtils.error("Erro ao resetar NPC " + npcId + ": " + e.getMessage());
                }
            }
            
            LogUtils.info("Reset completo finalizado - " + reseted + " NPCs resetados para skin padrão");
            
        } catch (Exception e) {
            LogUtils.error("Erro durante reset de NPCs para skin padrão: " + e.getMessage());
        }
    }
    
    /**
     * Atualiza holograma para mostrar valores padrão quando não há dados
     */
    private void updateHologramToEmpty(String npcId) {
        try {
            ConfigurationSection npcSection = npcConfig.getConfigurationSection("npc_positions." + npcId);
            if (npcSection == null) return;
            
            double x = npcSection.getDouble("x");
            double y = npcSection.getDouble("y");
            double z = npcSection.getDouble("z");
            String worldName = npcSection.getString("world");
            
            if (worldName == null) return;
            
            World world = Bukkit.getWorld(worldName);
            if (world == null) return;
            
            Location location = new Location(world, x, y, z);
            int position = extractPositionFromId(npcId);
            
            
            String defaultTag = npcConfig.getString("holograma.vazio.tag", "&c&lNenhum Clan");
            String defaultLider = npcConfig.getString("holograma.vazio.lider", "&7[Aguardando]");
            String defaultPontos = npcConfig.getString("holograma.vazio.pontos", "0");
            
            
            List<String> templateLines = npcConfig.getStringList("holograma.linhas");
            if (templateLines.isEmpty()) {
                templateLines = Arrays.asList("&6Top #{posicao}", "{tag} - &e{pontos} pontos", "&fLíder: &e{lider}");
            }
            
            
            List<String> processedLines = new ArrayList<>();
            for (String line : templateLines) {
                String processedLine = line
                    .replace("{posicao}", String.valueOf(position))
                    .replace("{tag}", defaultTag)
                    .replace("{lider}", defaultLider)
                    .replace("{pontos}", defaultPontos);
                
                processedLines.add(ChatColor.translateAlternateColorCodes('&', processedLine));
            }
            
            
            Location hologramLocation = location.clone().add(0, 3.2, 0);
            
            
            hologramManager.removeHologram("hliga_" + npcId);
            hologramManager.createHologram("hliga_" + npcId, hologramLocation, processedLines);
            
            LogUtils.debug("Holograma de " + npcId + " atualizado com valores padrão");
            
        } catch (Exception e) {
            LogUtils.error("Erro ao atualizar holograma para valores padrão: " + e.getMessage());
        }
    }
    
    /**
     * Resetar skin do NPC para padrão (recrear NPC com skin padrão)
     */
    private boolean resetNPCSkinToDefault(String npcId, String skinValue, String skinSignature) {
        try {
            if (!CitizensAPI.hasImplementation()) {
                return false;
            }
            
            ConfigurationSection npcSection = npcConfig.getConfigurationSection("npc_positions." + npcId);
            if (npcSection == null) {
                return false;
            }
            
            
            double x = npcSection.getDouble("x");
            double y = npcSection.getDouble("y");
            double z = npcSection.getDouble("z");
            float yaw = (float) npcSection.getDouble("yaw", 0);
            float pitch = (float) npcSection.getDouble("pitch", 0);
            String worldName = npcSection.getString("world");
            
            if (worldName == null) {
                return false;
            }
            
            World world = Bukkit.getWorld(worldName);
            if (world == null) {
                return false;
            }
            
            Location location = new Location(world, x, y, z, yaw, pitch);
            
            
            removePhysicalNPC(npcId);
            
            
            NPCRegistry registry = CitizensAPI.getNPCRegistry();
            NPC newNpc = registry.createNPC(EntityType.PLAYER, "hLiga_" + npcId);
            newNpc.data().set("hliga_id", npcId);
            ensureNPCNonPersistent(newNpc);
            
            
            if (!skinValue.isEmpty() && !skinSignature.isEmpty()) {
                try {
                    
                    Class<?> skinTraitClass = Class.forName("net.citizensnpcs.trait.SkinTrait");
                    Object skinTrait = newNpc.getOrAddTrait((Class) skinTraitClass);
                    
                    skinTraitClass.getMethod("setSkinPersistent", String.class, String.class, String.class)
                        .invoke(skinTrait, "skin_vazio", skinSignature, skinValue);
                    
                } catch (Exception e) {
                    LogUtils.debug("Skin padrão não pôde ser aplicada: " + e.getMessage());
                }
            }
            
            
            newNpc.spawn(location);
            
            
            npcIds.put(npcId, newNpc.getId());
            
            LogUtils.debug("NPC " + npcId + " recriado com skin padrão");
            return true;
            
        } catch (Exception e) {
            LogUtils.error("Erro ao resetar skin do NPC " + npcId + ": " + e.getMessage());
            return false;
        }
    }
    

    
    /**
     * Reseta NPC para skin padrão definida no tops.yml
     */
    private boolean resetNPCToDefaultSkin(String npcId, String skinName) {
        try {
            LogUtils.debug("Resetando NPC " + npcId + " para skin padrão: " + skinName);
            
            
            
            
            
            Integer citizensId = npcIds.get(npcId);
            if (citizensId == null) {
                LogUtils.debug("NPC " + npcId + " não encontrado no mapeamento");
                return false;
            }
            
            
            ConfigurationSection npcSection = npcConfig.getConfigurationSection("npc_positions." + npcId);
            if (npcSection != null) {
                double x = npcSection.getDouble("x");
                double y = npcSection.getDouble("y");
                double z = npcSection.getDouble("z");
                String worldName = npcSection.getString("world");
                
                if (worldName != null) {
                    World world = Bukkit.getWorld(worldName);
                    if (world != null) {
                        Location location = new Location(world, x, y, z);
                        
                        
                        removeNPC(npcId);
                        
                        
                        int position = extractPositionFromId(npcId);
                        
                        
                        createNPC(npcId, position, location);
                        
                        LogUtils.debug("NPC " + npcId + " recriado com skin padrão");
                        return true;
                    }
                }
            }
            
            return false;
            
        } catch (Exception e) {
            LogUtils.error("Erro ao resetar NPC " + npcId + " para skin padrão: " + e.getMessage());
            return false;
        }
    }
}