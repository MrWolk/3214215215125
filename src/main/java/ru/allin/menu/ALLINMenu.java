package ru.allin.menu;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.*;
import org.bukkit.command.*;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.lang.reflect.Field;
import java.text.DecimalFormat;
import java.time.Duration;
import java.util.*;

public final class ALLINMenu extends JavaPlugin implements Listener, CommandExecutor {
    private YamlConfiguration menu, masters, weekly;
    private File menuFile, mastersFile, weeklyFile;
    private final Map<UUID, TeleportSession> teleports = new HashMap<>();
    private Economy economy;
    private final NamespacedKey masterKey = new NamespacedKey(this, "master-id");

    private enum Profession {
        MINER("miner", "Шахтёр", "ALLINMiner", Material.DIAMOND_PICKAXE, 5, "progress", new int[]{1000,3000,5000,7000}),
        PROCESSOR("processor", "Переработчик", "ALLINProcessor", Material.BLAST_FURNACE, 3, "progress", new int[]{2000,6000}),
        FARMER("farmer", "Фермер", "ALLINFermer", Material.DIAMOND_HOE, 5, "progress", new int[]{1000,3000,5000,7000}),
        FISHING("fishing", "Рыбак", "ALLINFishing", Material.FISHING_ROD, 5, "progress", new int[]{100,300,500,700}),
        LUMBERJACK("lumberjack", "Лесоруб", "ALLINLumberjack", Material.DIAMOND_AXE, 5, "progress", new int[]{500,1500,2500,3500}),
        ALCHEMIST("alchemist", "Алхимик", "ALLINAlchemist", Material.BREWING_STAND, 3, "progress", new int[]{1500,3000}),
        HUNTER("hunter", "Охотник", "ALLINHunter", Material.BOW, 4, "souls", new int[]{10000,100000,1000000});

        final String id, title, pluginName, valueField;
        final Material material;
        final int maxLevel;
        final int[] thresholds;
        Profession(String id,String title,String pluginName,Material material,int maxLevel,String valueField,int[] thresholds){
            this.id=id;this.title=title;this.pluginName=pluginName;this.material=material;this.maxLevel=maxLevel;this.valueField=valueField;this.thresholds=thresholds;
        }
        static Profession byId(String id){ for(Profession p:values()) if(p.id.equalsIgnoreCase(id)) return p; return null; }
    }

    private record Stat(int level, long progress) {}
    private record Leader(UUID uuid, String name, long score, int level, long progress) {}

    private static final class GuiHolder implements InventoryHolder {
        final String type;
        final String data;
        GuiHolder(String type){this(type,"");}
        GuiHolder(String type,String data){this.type=type;this.data=data;}
        @Override public Inventory getInventory(){return null;}
    }

    @Override public void onEnable() {
        saveDefaultConfig();
        saveResource("menu.yml", false);
        saveResource("masters.yml", false);
        loadFiles();
        setupEconomy();
        Objects.requireNonNull(getCommand("menu")).setExecutor(this);
        Objects.requireNonNull(getCommand("allinmenu")).setExecutor(this);
        getServer().getPluginManager().registerEvents(this, this);
        getServer().getScheduler().runTaskTimer(this, this::checkWeeklyPayout, 20L*30, 20L*60);
        getLogger().info("ALLINMenu 1.1.0 enabled");
    }

    @Override public void onDisable() {
        teleports.values().forEach(TeleportSession::cancel);
        teleports.clear();
        saveWeekly();
    }

    private void loadFiles() {
        reloadConfig();
        menuFile = new File(getDataFolder(), "menu.yml");
        mastersFile = new File(getDataFolder(), "masters.yml");
        weeklyFile = new File(getDataFolder(), "weekly.yml");
        menu = YamlConfiguration.loadConfiguration(menuFile);
        masters = YamlConfiguration.loadConfiguration(mastersFile);
        weekly = YamlConfiguration.loadConfiguration(weeklyFile);
    }

    private void setupEconomy(){
        RegisteredServiceProvider<Economy> rsp=getServer().getServicesManager().getRegistration(Economy.class);
        economy=rsp==null?null:rsp.getProvider();
        if(economy==null) getLogger().warning("Vault Economy не найден: еженедельные выплаты ТОПов отключены до появления экономики.");
    }

    private String c(String s){return ChatColor.translateAlternateColorCodes('&',s==null?"":s);}
    private List<String> c(List<String> list){List<String> out=new ArrayList<>();for(String s:list)out.add(c(s));return out;}
    private void msg(Player p,String key){p.sendMessage(c(getConfig().getString("messages.prefix","")+getConfig().getString("messages."+key,"")));}
    private void msg(Player p,String key,String name,int sec){String s=getConfig().getString("messages.prefix","")+getConfig().getString("messages."+key,"");p.sendMessage(c(s.replace("{name}",name).replace("{seconds}",String.valueOf(sec))));}

    @Override public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if(cmd.getName().equalsIgnoreCase("menu")){
            if(!(sender instanceof Player p)){sender.sendMessage("Players only.");return true;}
            if(!p.hasPermission("allinmenu.use")){msg(p,"no-permission");return true;}
            openMain(p);return true;
        }
        if(!sender.hasPermission("allinmenu.admin")){if(sender instanceof Player p)msg(p,"no-permission");return true;}
        if(args.length==0){help(sender);return true;}
        if(args[0].equalsIgnoreCase("reload")){loadFiles();setupEconomy();sender.sendMessage(c("&aALLINMenu перезагружен."));return true;}
        if(args[0].equalsIgnoreCase("master"))return masterCommand(sender,args);
        if(args[0].equalsIgnoreCase("tops"))return topsCommand(sender,args);
        help(sender);return true;
    }

    private boolean topsCommand(CommandSender s,String[] a){
        if(a.length<2){
            s.sendMessage(c("&e/allinmenu tops start &7— запустить 7-дневный отсчёт"));
            s.sendMessage(c("&e/allinmenu tops stop &7— остановить выплаты"));
            s.sendMessage(c("&e/allinmenu tops status &7— статус"));
            return true;
        }
        String sub=a[1].toLowerCase(Locale.ROOT);
        if(sub.equals("start")){
            long next=System.currentTimeMillis()+Duration.ofDays(7).toMillis();
            weekly.set("running",true);weekly.set("next-payout",next);saveWeekly();
            s.sendMessage(c("&aОтсчёт ТОПов запущен. Первая выплата &e$5000 &aпо каждой профессии — через 7 дней."));
            return true;
        }
        if(sub.equals("stop")){weekly.set("running",false);saveWeekly();s.sendMessage(c("&cЕженедельные выплаты ТОПов остановлены."));return true;}
        if(sub.equals("status")){
            if(!weekly.getBoolean("running",false)){s.sendMessage(c("&7ТОП-неделя сейчас &cне запущена&7."));return true;}
            s.sendMessage(c("&7До следующей выплаты: &e"+formatDuration(Math.max(0,weekly.getLong("next-payout")-System.currentTimeMillis()))));return true;
        }
        return topsCommand(s,new String[]{"tops"});
    }

    private boolean masterCommand(CommandSender s,String[] a){
        if(a.length<2){help(s);return true;}
        String sub=a[1].toLowerCase(Locale.ROOT);
        try{
            if(sub.equals("list")){
                ConfigurationSection sec=masters.getConfigurationSection("masters");s.sendMessage(c("&6Мастера обмена:"));
                if(sec==null||sec.getKeys(false).isEmpty())s.sendMessage(c("&7Нет настроенных мастеров."));
                else for(String id:sec.getKeys(false))s.sendMessage(c("&e"+id+"&7 — "+masters.getString("masters."+id+".name",id)));
                return true;
            }
            if(sub.equals("set")&&s instanceof Player p&&a.length>=4){
                String id=a[2].toLowerCase(Locale.ROOT),name=String.join(" ",Arrays.copyOfRange(a,3,a.length));Location l=p.getLocation();String path="masters."+id;
                masters.set(path+".name",name);masters.set(path+".world",l.getWorld().getName());masters.set(path+".x",l.getX());masters.set(path+".y",l.getY());masters.set(path+".z",l.getZ());masters.set(path+".yaw",l.getYaw());masters.set(path+".pitch",l.getPitch());masters.save(mastersFile);
                s.sendMessage(c("&aМастер &e"+name+"&a сохранён в текущей точке. ID: &f"+id));return true;
            }
            if(sub.equals("delete")&&a.length>=3){String id=a[2].toLowerCase(Locale.ROOT);masters.set("masters."+id,null);masters.save(mastersFile);s.sendMessage(c("&aМастер &e"+id+"&a удалён."));return true;}
            if(sub.equals("rename")&&a.length>=4){String id=a[2].toLowerCase(Locale.ROOT);if(!masters.contains("masters."+id)){s.sendMessage(c("&cМастер не найден."));return true;}String name=String.join(" ",Arrays.copyOfRange(a,3,a.length));masters.set("masters."+id+".name",name);masters.save(mastersFile);s.sendMessage(c("&aНазвание изменено на &e"+name));return true;}
        }catch(Exception e){s.sendMessage(c("&cОшибка: "+e.getMessage()));getLogger().warning(e.toString());return true;}
        help(s);return true;
    }

    private void help(CommandSender s){
        s.sendMessage(c("&6ALLINMenu admin:"));
        s.sendMessage(c("&e/allinmenu master set <id> <название>"));
        s.sendMessage(c("&e/allinmenu master rename <id> <название>"));
        s.sendMessage(c("&e/allinmenu master delete <id>"));
        s.sendMessage(c("&e/allinmenu master list"));
        s.sendMessage(c("&e/allinmenu tops start|stop|status"));
        s.sendMessage(c("&e/allinmenu reload"));
    }

    private ItemStack item(Material mat,String name,List<String> lore){ItemStack it=new ItemStack(mat);ItemMeta m=it.getItemMeta();m.setDisplayName(c(name));m.setLore(c(lore));it.setItemMeta(m);return it;}
    private Material mat(String s,Material def){try{return Material.valueOf(s.toUpperCase(Locale.ROOT));}catch(Exception e){return def;}}
    private Inventory gui(String type,String data,int size,String title){return Bukkit.createInventory(new GuiHolder(type,data),size,c(title));}
    private void fill(Inventory inv){ItemStack f=item(mat(menu.getString("filler","BLACK_STAINED_GLASS_PANE"),Material.BLACK_STAINED_GLASS_PANE)," ",List.of());for(int i=0;i<inv.getSize();i++)inv.setItem(i,f);}

    private void openMain(Player p){
        int size=menu.getInt("size",54);Inventory inv=gui("main","",size,menu.getString("title","&0ALLINONLINE"));fill(inv);
        ConfigurationSection sec=menu.getConfigurationSection("items");if(sec!=null)for(String id:sec.getKeys(false)){String path="items."+id;int slot=menu.getInt(path+".slot",-1);if(slot>=0&&slot<size)inv.setItem(slot,item(mat(menu.getString(path+".material"),Material.PAPER),menu.getString(path+".name",id),menu.getStringList(path+".lore")));}
        p.openInventory(inv);
    }

    private void openJobs(Player p){
        Inventory inv=gui("jobs","",54,"&0Работы и профессии");fill(inv);
        int[] slots={10,12,14,16,28,30,32};int i=0;
        for(Profession prof:Profession.values()){
            Stat st=readStat(prof,p.getUniqueId());
            List<String> lore=new ArrayList<>();lore.add("&7Ваш уровень: &e"+st.level+"/&f"+prof.maxLevel);lore.add("&7Общий счёт: &f"+totalScore(prof,st));lore.add("");lore.add("&eНажмите для подробной информации,");lore.add("&eпрогресса и наград уровней.");
            inv.setItem(slots[i++],item(prof.material,"&6&l"+prof.title,lore));
        }
        inv.setItem(49,item(Material.ARROW,"&eНазад",List.of("&7В главное меню")));p.openInventory(inv);
    }

    private void openProfession(Player p,Profession prof){
        Stat st=readStat(prof,p.getUniqueId());Inventory inv=gui("profession",prof.id,54,"&0Профессия: "+prof.title);fill(inv);
        List<String> status=new ArrayList<>();status.add("&7Ваш уровень: &e"+st.level+"/&f"+prof.maxLevel);status.add("&7Общий счёт: &f"+totalScore(prof,st));
        if(st.level<prof.maxLevel){long need=prof.thresholds[st.level-1];status.add("&7До следующего уровня: &f"+st.progress+" &7/ &e"+need);status.add(progressBar(st.progress,need));status.add("");status.add("&aСледующий уровень даст:");status.addAll(levelBenefit(prof,st.level+1));}
        else{status.add("&aМаксимальный уровень достигнут.");status.add("&7Счётчик продолжает учитываться для ТОПа.");}
        inv.setItem(13,item(prof.material,"&e&lВаш прогресс",status));
        inv.setItem(29,item(Material.BOOK,"&f&lКак работает профессия",professionDescription(prof)));
        inv.setItem(31,item(Material.EXPERIENCE_BOTTLE,"&a&lНаграды по уровням",allLevelBenefits(prof)));
        inv.setItem(33,item(Material.PAPER,"&b&lКоманды",professionCommands(prof,p)));
        inv.setItem(49,item(Material.ARROW,"&eНазад",List.of("&7К списку профессий")));p.openInventory(inv);
    }

    private List<String> professionDescription(Profession p){
        List<String> x=menu.getStringList("profession-details."+p.id+".description");return x.isEmpty()?List.of("&7Описание настраивается в menu.yml"):x;
    }
    private List<String> professionCommands(Profession p,Player player){
        List<String> x=new ArrayList<>();List<String> configured=menu.getStringList("profession-details."+p.id+".commands");
        if(configured.isEmpty())x.add("&7Отдельных команд игрока нет."); else x.addAll(configured);
        x.add("");x.add("&8Прогресс всегда можно посмотреть здесь через /menu.");return x;
    }
    private List<String> allLevelBenefits(Profession p){List<String> out=new ArrayList<>();for(int l=1;l<=p.maxLevel;l++){out.add("&eУровень "+l+":");out.addAll(levelBenefit(p,l));if(l<p.maxLevel)out.add("");}return out;}
    private List<String> levelBenefit(Profession p,int level){
        List<String> x=menu.getStringList("profession-details."+p.id+".levels."+level);return x.isEmpty()?List.of("&7Бонус уровня настраивается в menu.yml"):x;
    }

    private void openTops(Player p){
        Inventory inv=gui("tops","",54,"&0ТОПы профессий");fill(inv);int[] slots={10,12,14,16,28,30,32};int i=0;
        for(Profession prof:Profession.values()){
            Leader l=findLeader(prof);List<String> lore=new ArrayList<>();
            if(l==null){lore.add("&7Пока нет результатов.");}else{lore.add("&7Лучший игрок: &e"+l.name);lore.add("&7Уровень: &f"+l.level);lore.add("&7Счёт: &a"+l.score);}
            lore.add("");lore.add("&6Награда недели: &e$5000");inv.setItem(slots[i++],item(prof.material,"&6&l"+prof.title,lore));
        }
        List<String> info=new ArrayList<>();info.add("&7Каждые 7 дней лидер каждой профессии");info.add("&7получает на денежный счёт &a$5000&7.");info.add("");
        if(weekly.getBoolean("running",false))info.add("&7До выплаты: &e"+formatDuration(Math.max(0,weekly.getLong("next-payout")-System.currentTimeMillis())));else info.add("&cОтсчёт ещё не запущен администратором.");
        inv.setItem(4,item(Material.NETHER_STAR,"&e&lЕженедельный рейтинг",info));inv.setItem(49,item(Material.ARROW,"&eНазад",List.of("&7В главное меню")));p.openInventory(inv);
    }

    private void openWorkbenchInfo(Player p){
        Inventory inv=gui("workbench","",54,"&0Прокачка верстака");fill(inv);
        inv.setItem(20,item(Material.CRAFTING_TABLE,"&e&lВерстак I",List.of("&7Открывает серверный крафт.","&7Шанс успешного создания: &e50%","&7Шанс случайного зачарования I: &a10%","","&fУлучшение 0 → 1:","&71 звезда Незера","&7192 дубовых бревна","&75 обломков незерита")));
        inv.setItem(22,item(Material.CRAFTING_TABLE,"&6&lВерстак II",List.of("&7Надёжнее создаёт предметы.","&7Шанс успешного создания: &e70%","&7Шанс случайного зачарования II: &a15%","","&fУлучшение 1 → 2:","&75 звёзд Незера","&7256 дубовых + 256 берёзовых брёвен","&716 обломков незерита")));
        inv.setItem(24,item(Material.CRAFTING_TABLE,"&c&lВерстак III",List.of("&7Максимальный уровень верстака.","&7Шанс успешного создания: &e80%","&7Шанс случайного зачарования III: &a20%","","&fУлучшение 2 → 3:","&715 звёзд Незера","&7512 дубовых + 512 берёзовых брёвен","&732 обломка незерита")));
        inv.setItem(40,item(Material.ANVIL,"&f&lЗачем прокачивать?",List.of("&7Чем выше уровень, тем меньше риск","&7потерять ресурсы при неудачном крафте","&7и тем выше шанс получить предмет","&7с более сильным зачарованием.","","&7Крафт занимает &e30 секунд&7.")));
        inv.setItem(49,item(Material.ARROW,"&eНазад",List.of("&7В главное меню")));p.openInventory(inv);
    }

    @SuppressWarnings("unchecked") private void openCommandPage(Player p,int index){
        List<Map<?,?>> pages=menu.getMapList("command-pages");if(pages.isEmpty())return;index=Math.max(0,Math.min(index,pages.size()-1));Map<?,?> data=pages.get(index);Object titleObj=data.get("title");String title=titleObj==null?"&0Команды":String.valueOf(titleObj);List<String> lines=new ArrayList<>();Object raw=data.get("lines");if(raw instanceof List<?> list)for(Object o:list)lines.add(String.valueOf(o));
        Inventory inv=gui("commands",String.valueOf(index),54,title);fill(inv);inv.setItem(22,item(Material.BOOK,"&b&lКоманды игрока",lines));if(index>0)inv.setItem(45,item(Material.ARROW,"&eПредыдущая",List.of()));inv.setItem(49,item(Material.BARRIER,"&cВ главное меню",List.of()));if(index+1<pages.size())inv.setItem(53,item(Material.ARROW,"&eСледующая",List.of()));p.openInventory(inv);
    }

    private void openInfo(Player p,String page){String base="pages."+page;Inventory inv=gui("page",page,54,menu.getString(base+".title","&0Информация"));fill(inv);inv.setItem(22,item(Material.BOOK,"&e&lИнформация",menu.getStringList(base+".lines")));inv.setItem(49,item(Material.ARROW,"&eНазад",List.of("&7В главное меню")));p.openInventory(inv);}

    private void openMasters(Player p){
        ConfigurationSection sec=masters.getConfigurationSection("masters");if(sec==null||sec.getKeys(false).isEmpty()){msg(p,"no-masters");return;}List<String> ids=new ArrayList<>(sec.getKeys(false));int size=Math.min(54,Math.max(9,((ids.size()+8)/9)*9));Inventory inv=gui("masters","",size,"&0Мастера обмена");fill(inv);
        for(int i=0;i<ids.size()&&i<size;i++){String id=ids.get(i),name=masters.getString("masters."+id+".name",id);ItemStack it=item(Material.EMERALD,"&a&l"+name,List.of("&7Телепортация: &f"+getConfig().getInt("teleport.seconds",30)+" сек.","&cВо время ожидания нельзя двигаться.","","&eНажмите для телепортации."));ItemMeta im=it.getItemMeta();im.getPersistentDataContainer().set(masterKey,PersistentDataType.STRING,id);it.setItemMeta(im);inv.setItem(i,it);}p.openInventory(inv);
    }

    private void sendTelegram(Player p){p.closeInventory();String url=menu.getString("telegram-url","https://t.me/allinonlinerp");p.sendMessage(c("&8&m--------------------------------"));p.sendMessage(c("&b&lALLINONLINE &f— Telegram"));p.sendMessage(Component.text("► ОТКРЫТЬ TELEGRAM ◄",NamedTextColor.AQUA).decorate(TextDecoration.BOLD).clickEvent(ClickEvent.openUrl(url)));p.sendMessage(c("&7"+url));p.sendMessage(c("&8&m--------------------------------"));}
    private void sendDonate(Player p){p.closeInventory();String url=menu.getString("donate-url","https://allinonline.easydonate.ru");p.sendMessage(c("&8&m--------------------------------"));p.sendMessage(c("&d&lALLINONLINE &f— официальный донат-магазин"));p.sendMessage(Component.text("► ОТКРЫТЬ МАГАЗИН ◄",NamedTextColor.GREEN).decorate(TextDecoration.BOLD).clickEvent(ClickEvent.openUrl(url)));p.sendMessage(c("&7"+url));p.sendMessage(c("&8&m--------------------------------"));}

    @EventHandler(priority=EventPriority.HIGHEST) public void click(InventoryClickEvent e){
        if(!(e.getView().getTopInventory().getHolder() instanceof GuiHolder h))return;e.setCancelled(true);if(!(e.getWhoClicked() instanceof Player p))return;if(e.getRawSlot()<0||e.getRawSlot()>=e.getView().getTopInventory().getSize())return;ItemStack current=e.getCurrentItem();if(current==null||current.getType()==Material.AIR)return;int slot=e.getRawSlot();
        switch(h.type){
            case "main" -> handleMain(p,slot);
            case "jobs" -> {if(slot==49)openMain(p);else{int[] slots={10,12,14,16,28,30,32};for(int i=0;i<slots.length;i++)if(slot==slots[i]){openProfession(p,Profession.values()[i]);break;}}}
            case "profession" -> {if(slot==49)openJobs(p);}
            case "tops","workbench","page" -> {if(slot==49)openMain(p);}
            case "commands" -> {int idx=Integer.parseInt(h.data);if(slot==45&&idx>0)openCommandPage(p,idx-1);else if(slot==49)openMain(p);else if(slot==53&&idx+1<menu.getMapList("command-pages").size())openCommandPage(p,idx+1);}
            case "masters" -> {ItemMeta im=current.getItemMeta();String id=im.getPersistentDataContainer().get(masterKey,PersistentDataType.STRING);if(id!=null){p.closeInventory();startTeleport(p,id);}}
        }
    }

    @EventHandler(priority=EventPriority.HIGHEST) public void drag(InventoryDragEvent e){if(e.getView().getTopInventory().getHolder() instanceof GuiHolder)e.setCancelled(true);}

    private void handleMain(Player p,int slot){
        ConfigurationSection sec=menu.getConfigurationSection("items");if(sec==null)return;for(String id:sec.getKeys(false)){String path="items."+id;if(menu.getInt(path+".slot",-1)!=slot)continue;String action=menu.getString(path+".action","");String page=menu.getString(path+".page","");
            if(action.equalsIgnoreCase("close"))p.closeInventory();else if(action.equalsIgnoreCase("masters"))openMasters(p);else if(action.equalsIgnoreCase("jobs"))openJobs(p);else if(action.equalsIgnoreCase("commands"))openCommandPage(p,0);else if(action.equalsIgnoreCase("donate"))sendDonate(p);else if(action.equalsIgnoreCase("telegram"))sendTelegram(p);else if(action.equalsIgnoreCase("tops"))openTops(p);else if(action.equalsIgnoreCase("workbench"))openWorkbenchInfo(p);else if(action.equalsIgnoreCase("factions")){p.closeInventory();Bukkit.getScheduler().runTask(this,()->p.performCommand("faction"));}else if(!page.isBlank())openInfo(p,page);return;}
    }

    private Stat readStat(Profession prof,UUID uuid){
        Stat live=readLiveStat(prof,uuid);if(live!=null)return live;File f=new File(getServer().getPluginsFolder(),prof.pluginName+File.separator+"players.yml");if(!f.exists())return new Stat(1,0);YamlConfiguration y=YamlConfiguration.loadConfiguration(f);return new Stat(Math.max(1,y.getInt("players."+uuid+".level",1)),Math.max(0,y.getLong("players."+uuid+"."+prof.valueField,0)));
    }

    private Stat readLiveStat(Profession prof,UUID uuid){
        try{Plugin plugin=getServer().getPluginManager().getPlugin(prof.pluginName);if(plugin==null||!plugin.isEnabled())return null;Field sf=findField(plugin.getClass(),"stats");if(sf==null)return null;sf.setAccessible(true);Object obj=sf.get(plugin);if(!(obj instanceof Map<?,?> map))return null;Object stat=map.get(uuid);if(stat==null)return null;Field lf=findField(stat.getClass(),"level"),pf=findField(stat.getClass(),prof.valueField);if(lf==null||pf==null)return null;lf.setAccessible(true);pf.setAccessible(true);return new Stat(((Number)lf.get(stat)).intValue(),((Number)pf.get(stat)).longValue());}catch(Throwable ignored){return null;}
    }
    private Field findField(Class<?> c,String name){while(c!=null){try{return c.getDeclaredField(name);}catch(NoSuchFieldException e){c=c.getSuperclass();}}return null;}

    private Map<UUID,Stat> allStats(Profession prof){
        Map<UUID,Stat> out=new HashMap<>();File f=new File(getServer().getPluginsFolder(),prof.pluginName+File.separator+"players.yml");if(f.exists()){YamlConfiguration y=YamlConfiguration.loadConfiguration(f);ConfigurationSection sec=y.getConfigurationSection("players");if(sec!=null)for(String id:sec.getKeys(false))try{UUID u=UUID.fromString(id);out.put(u,new Stat(Math.max(1,y.getInt("players."+id+".level",1)),Math.max(0,y.getLong("players."+id+"."+prof.valueField,0))));}catch(Exception ignored){}}
        try{Plugin plugin=getServer().getPluginManager().getPlugin(prof.pluginName);if(plugin!=null&&plugin.isEnabled()){Field sf=findField(plugin.getClass(),"stats");if(sf!=null){sf.setAccessible(true);Object obj=sf.get(plugin);if(obj instanceof Map<?,?> map)for(var e:map.entrySet())if(e.getKey() instanceof UUID u&&e.getValue()!=null){Object st=e.getValue();Field lf=findField(st.getClass(),"level"),pf=findField(st.getClass(),prof.valueField);if(lf!=null&&pf!=null){lf.setAccessible(true);pf.setAccessible(true);out.put(u,new Stat(((Number)lf.get(st)).intValue(),((Number)pf.get(st)).longValue()));}}}}}catch(Throwable ignored){}
        return out;
    }

    private long totalScore(Profession p,Stat s){long total=s.progress;int completed=Math.max(0,Math.min(s.level-1,p.thresholds.length));for(int i=0;i<completed;i++)total+=p.thresholds[i];return total;}
    private Leader findLeader(Profession p){Leader best=null;for(var e:allStats(p).entrySet()){long score=totalScore(p,e.getValue());OfflinePlayer op=Bukkit.getOfflinePlayer(e.getKey());String name=op.getName()==null?e.getKey().toString().substring(0,8):op.getName();if(best==null||score>best.score)best=new Leader(e.getKey(),name,score,e.getValue().level,e.getValue().progress);}return best;}

    private String progressBar(long value,long max){int n=20;double ratio=max<=0?1:Math.max(0,Math.min(1,value/(double)max));int filled=(int)Math.floor(ratio*n);return "&a"+"■".repeat(filled)+"&8"+"■".repeat(n-filled)+" &f"+(int)Math.floor(ratio*100)+"%";}

    private void checkWeeklyPayout(){
        if(!weekly.getBoolean("running",false))return;long next=weekly.getLong("next-payout",0);long now=System.currentTimeMillis();if(next<=0){weekly.set("next-payout",now+Duration.ofDays(7).toMillis());saveWeekly();return;}if(now<next)return;
        performWeeklyPayout();long newNext=next+Duration.ofDays(7).toMillis();while(newNext<=now)newNext+=Duration.ofDays(7).toMillis();weekly.set("next-payout",newNext);saveWeekly();
    }

    private void performWeeklyPayout(){
        if(economy==null){setupEconomy();if(economy==null){getLogger().warning("Выплата ТОПов пропущена: Vault Economy недоступен.");return;}}
        double reward=getConfig().getDouble("tops.weekly-reward",5000.0);long stamp=System.currentTimeMillis();
        for(Profession p:Profession.values()){
            Leader l=findLeader(p);if(l==null||l.score<=0)continue;OfflinePlayer op=Bukkit.getOfflinePlayer(l.uuid);economy.depositPlayer(op,reward);String base="history."+stamp+"."+p.id;weekly.set(base+".uuid",l.uuid.toString());weekly.set(base+".name",l.name);weekly.set(base+".score",l.score);weekly.set(base+".reward",reward);Bukkit.broadcastMessage(c("&6&lТОП НЕДЕЛИ! &e"+l.name+" &7— лучший в профессии &f"+p.title+"&7. Награда: &a$"+new DecimalFormat("0").format(reward)));
        }
        saveWeekly();
    }

    private void saveWeekly(){try{weekly.save(weeklyFile);}catch(Exception e){getLogger().warning("Не удалось сохранить weekly.yml: "+e.getMessage());}}
    private String formatDuration(long ms){long total=Math.max(0,ms/1000);long d=total/86400;long h=(total%86400)/3600;long m=(total%3600)/60;return d+"д "+h+"ч "+m+"м";}

    private Location masterLocation(String id){String path="masters."+id,wn=masters.getString(path+".world");World w=wn==null?null:Bukkit.getWorld(wn);if(w==null)return null;return new Location(w,masters.getDouble(path+".x"),masters.getDouble(path+".y"),masters.getDouble(path+".z"),(float)masters.getDouble(path+".yaw"),(float)masters.getDouble(path+".pitch"));}
    private void startTeleport(Player p,String id){Location target=masterLocation(id);if(target==null){msg(p,"master-not-found");return;}if(teleports.containsKey(p.getUniqueId()))teleports.remove(p.getUniqueId()).cancel();int total=Math.max(1,getConfig().getInt("teleport.seconds",30));String name=masters.getString("masters."+id+".name",id);TeleportSession session=new TeleportSession(p,name,p.getLocation().clone(),target,total);teleports.put(p.getUniqueId(),session);session.start();msg(p,"teleport-start",name,total);}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void move(PlayerMoveEvent e){TeleportSession s=teleports.get(e.getPlayer().getUniqueId());if(s==null||e.getTo()==null)return;Location a=s.anchor,to=e.getTo();if(a.getWorld()!=to.getWorld()||Math.abs(a.getX()-to.getX())>.001||Math.abs(a.getY()-to.getY())>.001||Math.abs(a.getZ()-to.getZ())>.001){e.setTo(new Location(a.getWorld(),a.getX(),a.getY(),a.getZ(),to.getYaw(),to.getPitch()));s.cancel();teleports.remove(e.getPlayer().getUniqueId());msg(e.getPlayer(),"teleport-cancel-move");}}
    @EventHandler(ignoreCancelled=true) public void damage(EntityDamageEvent e){if(!getConfig().getBoolean("teleport.cancel-on-damage",false)||!(e.getEntity() instanceof Player p))return;TeleportSession s=teleports.remove(p.getUniqueId());if(s!=null){s.cancel();msg(p,"teleport-cancel-damage");}}

    private final class TeleportSession{
        final Player p;final String name;final Location anchor,target;int left;BukkitTask task;TeleportSession(Player p,String name,Location anchor,Location target,int left){this.p=p;this.name=name;this.anchor=anchor;this.target=target;this.left=left;}
        void start(){task=Bukkit.getScheduler().runTaskTimer(ALLINMenu.this,()->{if(!p.isOnline()){cancel();teleports.remove(p.getUniqueId());return;}if(left<=0){cancel();teleports.remove(p.getUniqueId());p.teleportAsync(target).thenRun(()->Bukkit.getScheduler().runTask(ALLINMenu.this,()->msg(p,"teleport-done",name,0)));return;}p.sendActionBar(c(getConfig().getString("messages.teleport-progress","&eТелепортация: &c{seconds} сек.").replace("{seconds}",String.valueOf(left))));if(left<=5||left%5==0)p.playSound(p.getLocation(),Sound.BLOCK_NOTE_BLOCK_HAT,.5f,1.2f);left--;},0L,20L);}
        void cancel(){if(task!=null&&!task.isCancelled())task.cancel();}
    }
}
