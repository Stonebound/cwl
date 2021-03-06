package ru.pearx.cwl.commands;

import org.spongepowered.api.Sponge;
import org.spongepowered.api.command.CommandException;
import org.spongepowered.api.command.CommandResult;
import org.spongepowered.api.command.CommandSource;
import org.spongepowered.api.command.args.CommandContext;
import org.spongepowered.api.command.spec.CommandExecutor;
import org.spongepowered.api.profile.GameProfile;
import org.spongepowered.api.service.whitelist.WhitelistService;
import org.spongepowered.api.text.Text;
import ru.pearx.cwl.CWL;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;

/*
 * Created by mrAppleXZ on 14.01.18 12:16.
 */
public class GenWhitelistCommand implements CommandExecutor
{
    private CWL cwl;

    public GenWhitelistCommand(CWL cwl)
    {
        this.cwl = cwl;
    }

    @Override
    public CommandResult execute(CommandSource src, CommandContext args) throws CommandException
    {
        try
        {
            cwl.syncWhitelist();
        }
        catch (Exception e)
        {
            throw new CommandException(Text.of("An exception occurred while syncing the whitelist!"), e);
        }

        return CommandResult.success();
    }
}
