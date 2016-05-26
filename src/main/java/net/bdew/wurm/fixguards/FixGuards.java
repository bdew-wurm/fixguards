package net.bdew.wurm.fixguards;

import com.wurmonline.server.DbConnector;
import com.wurmonline.server.Server;
import com.wurmonline.server.creatures.Communicator;
import com.wurmonline.server.creatures.Creature;
import com.wurmonline.server.creatures.Creatures;
import com.wurmonline.server.utils.DbUtilities;
import com.wurmonline.server.villages.Guard;
import com.wurmonline.server.villages.Village;
import com.wurmonline.server.villages.Villages;
import org.gotti.wurmunlimited.modloader.interfaces.PlayerMessageListener;
import org.gotti.wurmunlimited.modloader.interfaces.WurmServerMod;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashSet;
import java.util.logging.Level;
import java.util.logging.Logger;

public class FixGuards implements WurmServerMod, PlayerMessageListener {
    private static final Logger logger = Logger.getLogger("FixGuards");

    private static void logException(String msg, Throwable e) {
        if (logger != null)
            logger.log(Level.SEVERE, msg, e);
    }

    private static void logWarning(String msg) {
        if (logger != null)
            logger.log(Level.WARNING, msg);
    }

    private static void logInfo(String msg) {
        if (logger != null)
            logger.log(Level.INFO, msg);
    }

    @Override
    public boolean onPlayerMessage(Communicator communicator, String msg) {
        if (msg.equals("#fixguards")) {
            if (communicator.getPlayer().getPower() <= 0) {
                FixGuards.logWarning(String.format("Player %s tried to use #fixguards command", communicator.getPlayer().getName()));
            } else {
                Village v = Villages.getVillage(communicator.getPlayer().getTileX(), communicator.getPlayer().getTileY(), communicator.getPlayer().isOnSurface());
                if (v == null) {
                    communicator.sendAlertServerMessage("Village not found");
                    return true;
                }
                fixGuards(v);
                communicator.sendNormalServerMessage("Done.");
            }
            return true;
        }
        return false;
    }

    public void fixGuards(Village v) {
        HashSet<Long> guardIds = new HashSet<>();
        for (Guard g : v.getGuards()) {
            guardIds.add(g.getCreature().getWurmId());
        }

        Connection dbcon = null;
        PreparedStatement ps = null;
        ResultSet rs = null;
        try {
            dbcon = DbConnector.getZonesDbCon();
            ps = dbcon.prepareStatement("SELECT * FROM GUARDS WHERE VILLAGEID=?");
            ps.setInt(1, v.id);
            rs = ps.executeQuery();
            while (rs.next()) {
                final long wurmId = rs.getLong("WURMID");
                if (!guardIds.contains(wurmId)) {
                    PreparedStatement ps2 = dbcon.prepareStatement("DELETE FROM GUARDS WHERE WURMID=?");
                    try {
                        FixGuards.logInfo("Deleting missing guard - " + wurmId);
                        ps2.setLong(1, wurmId);
                        ps2.executeUpdate();
                    } finally {
                        DbUtilities.closeDatabaseObjects(ps2, null);
                    }
                }
            }
        } catch (SQLException sqx) {
            FixGuards.logException("Error in fixguards", sqx);
            return;
        } finally {
            DbUtilities.closeDatabaseObjects(ps, rs);
            DbConnector.returnConnection(dbcon);
        }

        for (Creature c : Creatures.getInstance().getCreatures()) {
            if (c.getVillageId() == v.id && ((c.getTemplate().getTemplateId() == 32) || (c.getTemplate().getTemplateId() == 33)) && !guardIds.contains(c.getWurmId())) {
                if (v.plan.getNumHiredGuards() > guardIds.size()) {
                    Server.getInstance().broadCastAction(c.getName() + " is reassigned to proper guard duty.", c, 5);
                    try {
                        Method m = Village.class.getDeclaredMethod("createGuard", Creature.class, Long.TYPE);
                        m.setAccessible(true);
                        m.invoke(v, c, System.currentTimeMillis());
                    } catch (NoSuchMethodException | InvocationTargetException | IllegalAccessException e) {
                        throw new RuntimeException(e);
                    }
                    guardIds.add(c.getWurmId());
                } else {
                    Server.getInstance().broadCastAction(c.getName() + " realizes " + c.getHeSheItString() + " shouldn't exist and promptly vanishes in a puff of logic.", c, 5);
                    v.removeCitizen(c);
                    c.destroy();
                }
            }
        }
    }

}
