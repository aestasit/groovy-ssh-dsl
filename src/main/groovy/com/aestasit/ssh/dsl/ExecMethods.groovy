/*
 * Copyright (C) 2011-2014 Aestas/IT
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.aestasit.ssh.dsl

import com.aestasit.ssh.ExecOptions
import com.aestasit.ssh.QuotingUtil;
import com.aestasit.ssh.SshException
import com.aestasit.ssh.log.LoggerOutputStream
import com.jcraft.jsch.Channel
import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.JSchException
import org.apache.commons.io.output.TeeOutputStream

import static groovy.lang.Closure.DELEGATE_FIRST

/**
 * Mix-in class for SessionDelegate implementing EXEC functionality.
 *
 * @author Andrey Adamovich
 *
 */
class ExecMethods {

  private static final int RETRY_DELAY = 1000

  CommandOutput execCmd(String... cmd){
	  execCmd(Arrays.asList(cmd))
  }

  CommandOutput execCmd(Collection cmd) {
      doExec(cmd2Cmdline(cmd), new ExecOptions(options.execOptions))
  }

  CommandOutput execCmd(@DelegatesTo(strategy = DELEGATE_FIRST, value = ExecOptionsDelegate) Closure cl) {
    cl.delegate = new ExecOptionsDelegate()
    cl.resolveStrategy = Closure.DELEGATE_FIRST
    cl()
    if (!cl.delegate.command) {
      new SshException('Remote command is not specified!')
    }
    doExec(cmd2Cmdline(cl.delegate.command),
      new ExecOptions(options.execOptions, cl.delegate.execOptions))
  }

  CommandOutput execCmd(Map execOptions) {
      doExec(cmd2Cmdline(execOptions.command),
          new ExecOptions(options.execOptions, execOptions))
  }


  CommandOutput exec(String cmd) {
    doExec(cmd, new ExecOptions(options.execOptions))
  }

  CommandOutput exec(GString cmd) {
    doExec(cmd?.toString(), new ExecOptions(options.execOptions))
  }

  CommandOutput exec(Collection<String> cmds) {
    doExec(cmds, new ExecOptions(options.execOptions))
  }

  CommandOutput parallelExec(Collection<String> cmds) {
    // TODO: implement
    null
  }

  CommandOutput exec(@DelegatesTo(strategy = DELEGATE_FIRST, value = ExecOptionsDelegate) Closure cl) {
    ExecOptionsDelegate delegate = new ExecOptionsDelegate()
    cl.delegate = delegate
    cl.resolveStrategy = DELEGATE_FIRST
    cl()
    if (!delegate.command) {
      new SshException('Remote command is not specified!')
    }
    doExec(delegate.command, new ExecOptions(options.execOptions, delegate.execOptions))
  }

  CommandOutput exec(Map<?, ?> execOptions) {
    doExec(execOptions?.command?.toString(), new ExecOptions(options.execOptions, execOptions))
  }

  /**
   * Execute the specified command and returns a boolean to
   * signal if the command execution was successful.
   *
   * @param cmd a command to execute remotely
   * @return true, if command was successful
   */
  boolean ok(String cmd) {
    doExec(cmd, new ExecOptions(failOnError: false, showOutput: false, showCommand: false)).exitStatus == 0
  }

  /**
   * Execute the specified command and returns a boolean to
   * signal if the command execution was unsuccessful.
   *
   * @param cmd a command to execute remotely
   * @return true, if command was unsuccessful
   */
  boolean fail(String cmd) {
    !ok(cmd)
  }

  def prefix(String prefix, @DelegatesTo(strategy = DELEGATE_FIRST, value = SessionDelegate) Closure cl) {
    def originalPrefix = options.execOptions.prefix
    options.execOptions.prefix = prefix
    def result = null
    try {
      cl.delegate = this
      cl.resolveStrategy = DELEGATE_FIRST
      result = cl()
    } finally {
      options.execOptions.prefix = originalPrefix
    }
    result
  }

  def suffix(String suffix, @DelegatesTo(strategy = DELEGATE_FIRST, value = SessionDelegate) Closure cl) {
    def originalSuffix = options.execOptions.suffix
    options.execOptions.suffix = suffix
    def result = null
    try {
      cl.delegate = this
      cl.resolveStrategy = DELEGATE_FIRST
      result = cl()
    } finally {
      options.execOptions.suffix = originalSuffix
    }
    result
  }

  private CommandOutput doExec(Collection cmds, ExecOptions execOptions) {
    CommandOutput commandOutput = null
    cmds.each { cmd ->
      commandOutput = doExec(cmd, new ExecOptions(options.execOptions, execOptions))
    }
    commandOutput
  }

  private CommandOutput doExec(String cmd, ExecOptions options) {
    connect()
    catchExceptions(options) {
      awaitTermination(executeCommand(cmd, options), options)
    }
  }

  private ChannelData executeCommand(String cmd, ExecOptions options) {
    session.timeout = options.maxWait
    String actualCommand = cmd
    if (options.escapeCharacters) {
      if (options.escapeCharacters.contains('\\')) {
        actualCommand = actualCommand.replace('\\', '\\\\')
      }
      options.escapeCharacters.each { ch ->
        if (ch != '\\') {
          actualCommand = actualCommand.replace(ch.toString(), '\\' + ch)
        }
      }
    }
    if (options.prefix) {
      actualCommand = "${options.prefix} ${actualCommand}"
    }
    if (options.suffix) {
      actualCommand = "${actualCommand} ${options.suffix}"
    }
    if (options.showCommand) {
      logger.info("> " + actualCommand)
    }
    ChannelExec channel = (ChannelExec) session.openChannel("exec")
    def savedOutput = new ByteArrayOutputStream()
    def output = savedOutput
    if (options.showOutput) {
      def systemOutput = new LoggerOutputStream(logger)
      output = new TeeOutputStream(savedOutput, systemOutput)
    }
    channel.command = actualCommand
    channel.outputStream = output
    channel.extOutputStream = output
    channel.setPty(options.usePty)
    channel.connect()
    new ChannelData(channel: channel, output: savedOutput)
  }

  class ChannelData {
    ByteArrayOutputStream output
    Channel channel
  }

  private CommandOutput awaitTermination(ChannelData channelData, ExecOptions options) {
    Channel channel = channelData.channel
    try {
      def thread = null
      thread =
          new Thread() {
            void run() {
              while (!channel.isClosed()) {
                if (thread == null) {
                  return
                }
                try {
                  sleep(RETRY_DELAY)
                } catch (Exception e) {
                  // ignored
                }
              }
            }
          }
      thread.start()
      thread.join(options.maxWait)
      if (thread.isAlive()) {
        thread = null
        return failWithTimeout(options)
      } else {
        int ec = channel.exitStatus
        verifyExitCode(ec, options)
        return new CommandOutput(ec, channelData.output.toString())
      }
    } finally {
      channel.disconnect()
    }
  }

  private CommandOutput catchExceptions(ExecOptions options, Closure cl) {
    try {
      return cl()
    } catch (JSchException e) {
      if (e.getMessage().indexOf("session is down") >= 0) {
        return failWithTimeout(options)
      } else {
        return failWithException(options, e)
      }
    }
  }

  private CommandOutput failWithTimeout(ExecOptions options) {
    setChanged(true)
    if (options.failOnError) {
      throw new SshException("Session timeout!")
    } else {
      logger.warn("Session timeout!")
      return new CommandOutput(-1, "Session timeout!")
    }
  }

  private CommandOutput failWithException(ExecOptions options, Throwable e) {
    if (options.failOnError) {
      throw new SshException("Command failed with exception", e)
    } else {
      logger.warn("Caught exception: " + e.getMessage())
      return new CommandOutput(-1, e.getMessage(), e)
    }
  }

  private CommandOutput verifyExitCode(int exitCode, ExecOptions options) {
    if (exitCode != 0) {
      String msg = "Remote command failed with exit status $exitCode"
      if (options.failOnError) {
        throw new SshException(msg)
      } else {
        if (options.showOutput) {
          logger.warn(msg)
        }
      }
    }
    // TODO: missing return?
  }

  private static String cmd2Cmdline(Collection cmd) {
	  return cmd.collect {QuotingUtil.quoteUNIXArgument(it)}.join(' ')
  }
}
