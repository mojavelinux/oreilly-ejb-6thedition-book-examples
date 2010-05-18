/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2010, Red Hat Middleware LLC, and individual contributors
 * as indicated by the @author tags. See the copyright.txt file in the
 * distribution for a full listing of individual contributors.
  *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */
package org.jboss.ejb3.examples.chxx.transactions;

import java.math.BigDecimal;
import java.util.concurrent.Callable;
import java.util.logging.Logger;

import javax.naming.Context;
import javax.naming.InitialContext;
import javax.persistence.EntityManager;

import junit.framework.Assert;

import org.jboss.arquillian.api.Deployment;
import org.jboss.arquillian.api.RunMode;
import org.jboss.arquillian.api.RunModeType;
import org.jboss.arquillian.junit.Arquillian;
import org.jboss.ejb3.examples.chxx.transactions.api.BankLocalBusiness;
import org.jboss.ejb3.examples.chxx.transactions.api.PokerGameLocalBusiness;
import org.jboss.ejb3.examples.chxx.transactions.ejb.DbInitializerBean;
import org.jboss.ejb3.examples.chxx.transactions.ejb.ExampleUserData;
import org.jboss.ejb3.examples.chxx.transactions.ejb.ForcedTestException;
import org.jboss.ejb3.examples.chxx.transactions.ejb.TaskExecutionException;
import org.jboss.ejb3.examples.chxx.transactions.ejb.TxWrappingLocalBusiness;
import org.jboss.ejb3.examples.chxx.transactions.entity.Account;
import org.jboss.ejb3.examples.chxx.transactions.entity.User;
import org.jboss.ejb3.examples.chxx.transactions.impl.BankBean;
import org.jboss.ejb3.examples.chxx.transactions.impl.PokerServiceConstants;
import org.jboss.ejb3.examples.testsupport.dbinit.DbInitializerLocalBusiness;
import org.jboss.ejb3.examples.testsupport.dbquery.DbQueryBean;
import org.jboss.ejb3.examples.testsupport.dbquery.DbQueryLocalBusiness;
import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.junit.After;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Test cases to ensure that the Poker Game
 * is respecting transactional boundaries at the appropriate
 * granularity.
 *
 * @author <a href="mailto:andrew.rubinger@jboss.org">ALR</a>
 * @version $Revision: $
 */
@RunWith(Arquillian.class)
@RunMode(RunModeType.LOCAL)
public class TransactionalPokerGameIntegrationTest
{

   //-------------------------------------------------------------------------------------||
   // Class Members ----------------------------------------------------------------------||
   //-------------------------------------------------------------------------------------||

   /**
    * Logger
    */
   private static final Logger log = Logger.getLogger(TransactionalPokerGameIntegrationTest.class.getName());

   /**
    * Naming Context
    * @deprecated Remove when Arquillian will inject the EJB proxies
    */
   @Deprecated
   private static Context jndiContext;

   /**
    * The Deployment into the EJB Container
    */
   @Deployment
   public static JavaArchive getDeployment()
   {
      final JavaArchive archive = ShrinkWrap.create("test.jar", JavaArchive.class).addPackages(true,
            BankLocalBusiness.class.getPackage(), User.class.getPackage()).addManifestResource("persistence.xml")
            .addPackages(false, DbInitializerBean.class.getPackage(), BankBean.class.getPackage(),
                  DbInitializerLocalBusiness.class.getPackage(), DbQueryBean.class.getPackage());
      log.info(archive.toString(true));
      return archive;
   }

   //-------------------------------------------------------------------------------------||
   // Instance Members -------------------------------------------------------------------||
   //-------------------------------------------------------------------------------------||

   /**
    * Test-only DB initializer to sanitize and prepopulate the DB with each test run
    */
   // TODO: Support Injection of @EJB here when Arquillian for Embedded JBossAS will support it
   private DbInitializerLocalBusiness dbInitializer;

   /**
    * EJB which wraps supplied {@link Callable} instances inside of a new Tx
    */
   // TODO: Support Injection of @EJB here when Arquillian for Embedded JBossAS will support it
   private TxWrappingLocalBusiness txWrapper;

   /**
    * EJB which provides direct access to an {@link EntityManager}'s method for use in testing.
    * Must be called inside an existing Tx so that returned entities are not detached.
    */
   // TODO: Support Injection of @EJB here when Arquillian for Embedded JBossAS will support it
   private DbQueryLocalBusiness db;

   /**
    * Bank EJB Proxy
    */
   // TODO: Support Injection of @EJB here when Arquillian for Embedded JBossAS will support it
   private BankLocalBusiness bank;

   /**
    * Poker Game EJB Proxy
    */
   // TODO: Support Injection of @EJB here when Arquillian for Embedded JBossAS will support it
   private PokerGameLocalBusiness pokerGame;

   //-------------------------------------------------------------------------------------||
   // Lifecycle --------------------------------------------------------------------------||
   //-------------------------------------------------------------------------------------||

   /**
    * Performs suite-wide initialization
    */
   @BeforeClass
   public static void init() throws Exception
   {
      // After the server is up, we don't need to pass any explicit properties
      jndiContext = new InitialContext();
   }

   /**
    * Manually looks up EJBs in JNDI and assigns them
    * @deprecated Remove when Arquillian will handle the injection for us
    */
   @Deprecated
   @Before
   public void injectEjbs() throws Exception
   {
      // Fake injection by doing manual lookups for the time being
      dbInitializer = (DbInitializerLocalBusiness) jndiContext.lookup("DbInitializerBean/local");
      txWrapper = (TxWrappingLocalBusiness) jndiContext.lookup(TxWrappingLocalBusiness.JNDI_NAME);
      db = (DbQueryLocalBusiness) jndiContext.lookup("DbQueryBean/local");
      bank = (BankLocalBusiness) jndiContext.lookup(BankLocalBusiness.JNDI_NAME);
      pokerGame = (PokerGameLocalBusiness) jndiContext.lookup(PokerGameLocalBusiness.JNDI_NAME);
   }

   /**
    * Clears and repopulates the database with test data 
    * after each run
    * @throws Exception
    */
   @After
   public void refreshWithDefaultData() throws Exception
   {
      dbInitializer.refreshWithDefaultData();
   }

   //-------------------------------------------------------------------------------------||
   // Tests ------------------------------------------------------------------------------||
   //-------------------------------------------------------------------------------------||

   /**
    * Ensures that Transfers between accounts obey the ACID properties of Transactions
    */
   @Test
   public void transferRetainsIntegrity() throws Throwable
   {

      // Init
      final long alrubingerAccountId = ExampleUserData.ACCOUNT_ALRUBINGER_ID;
      final long pokerAccountId = PokerServiceConstants.ACCOUNT_POKERGAME_ID;

      // Ensure there's the expected amounts in both the ALR and Poker accounts
      final BigDecimal expectedinitialALR = ExampleUserData.INITIAL_ACCOUNT_BALANCE_ALR;
      final BigDecimal expectedinitialPoker = PokerServiceConstants.INITIAL_ACCOUNT_BALANCE_POKERGAME;
      this.executeInTx(new CheckBalanceOfAccountTask(alrubingerAccountId, expectedinitialALR),
            new CheckBalanceOfAccountTask(pokerAccountId, expectedinitialPoker));

      // Transfer $100 from ALR to Poker
      final BigDecimal oneHundred = new BigDecimal(100);
      bank.transfer(alrubingerAccountId, pokerAccountId, oneHundred);

      // Ensure there's $100 less in the ALR account, and $100 more in the poker account
      this.executeInTx(new CheckBalanceOfAccountTask(alrubingerAccountId, expectedinitialALR.subtract(oneHundred)),
            new CheckBalanceOfAccountTask(pokerAccountId, expectedinitialPoker.add(oneHundred)));

      // Now make a transfer, check it succeeded within the context of a Transaction, then 
      // intentionally throw an exception.  The Tx should complete as rolled back, 
      // and the state should be consistent (as if the xfer request never took place).
      boolean gotExpectedException = false;
      final Callable<Void> transferTask = new Callable<Void>()
      {
         @Override
         public Void call() throws Exception
         {
            bank.transfer(alrubingerAccountId, pokerAccountId, oneHundred);
            return null;
         }
      };
      try
      {
         this.executeInTx(transferTask, new CheckBalanceOfAccountTask(alrubingerAccountId, expectedinitialALR.subtract(
               oneHundred).subtract(oneHundred)), new CheckBalanceOfAccountTask(pokerAccountId, expectedinitialPoker
               .add(oneHundred).add(oneHundred)), ForcedTestExceptionTask.INSTANCE);
      }
      // Expected
      catch (final ForcedTestException fte)
      {
         gotExpectedException = true;
      }
      Assert.assertTrue("Did not receive expected exception as signaled from the test; was not rolled back",
            gotExpectedException);

      // Now that we've checked the transfer succeeded from within the Tx, then we threw an
      // exception before committed, ensure the Tx rolled back and the transfer was reverted from the 
      // perspective of everyone outside the Tx.
      this.executeInTx(new CheckBalanceOfAccountTask(alrubingerAccountId, expectedinitialALR.subtract(oneHundred)),
            new CheckBalanceOfAccountTask(pokerAccountId, expectedinitialPoker.add(oneHundred)));
   }

   /**
    * Ensures that when we make a sequence of bets enclosed in a single Tx,
    * some exceptional condition at the end doesn't roll back the prior
    * history.  Once we've won/lost a bet, that bet's done.  This tests that 
    * each bet takes place in its own isolated Tx. 
    */
   @Test
   public void sequenceOfBetsDoesntRollBackAll() throws Throwable
   {
      // Get the original balance for ALR; this is done outside a Tx
      final BigDecimal originalBalance = bank.getBalance(ExampleUserData.ACCOUNT_ALRUBINGER_ID);
      log.info("Starting balance before playing poker: " + originalBalance);

      // Execute 11 bets enclosed in a new Tx, and ensure that the account transfers
      // took place as expected.  Then throw an exception to rollback the parent Tx.
      final BigDecimal betAmount = new BigDecimal(20);
      final Place11BetsThenForceExceptionTask task = new Place11BetsThenForceExceptionTask(betAmount);
      boolean gotForcedException = false;
      try
      {
         this.executeInTx(task);
      }
      catch (final ForcedTestException tfe)
      {
         // Expected
         gotForcedException = true;
      }
      Assert.assertTrue("Did not obtain the test exception as expected", gotForcedException);

      // Now we've ensured that from inside the calling Tx we saw the account balances 
      // were as expected.  But we rolled back that enclosing Tx, so ensure that the outcome
      // of the games was not ignored
      final BigDecimal afterBetsBalance = bank.getBalance(ExampleUserData.ACCOUNT_ALRUBINGER_ID);
      final int gameOutcomeCount = task.gameOutcomeCount;
      new AssertGameOutcome(originalBalance, afterBetsBalance, gameOutcomeCount, betAmount).call();
   }

   //-------------------------------------------------------------------------------------||
   // Internal Helpers -------------------------------------------------------------------||
   //-------------------------------------------------------------------------------------||

   /**
    * Task which asserts given an account original balance, 
    * ending balance, game outcome count, and bet amount, that funds 
    * remaining are as expected.
    */
   private static final class AssertGameOutcome implements Callable<Void>
   {
      private final BigDecimal originalBalance;

      private final int gameOutcomeCount;

      private final BigDecimal betAmount;

      private final BigDecimal afterBetsBalance;

      AssertGameOutcome(final BigDecimal originalBalance, final BigDecimal afterBetsBalance,
            final int gameOutcomeCount, final BigDecimal betAmount)
      {
         this.originalBalance = originalBalance;
         this.gameOutcomeCount = gameOutcomeCount;
         this.betAmount = betAmount;
         this.afterBetsBalance = afterBetsBalance;
      }

      @Override
      public Void call() throws Exception
      {
         // Calculate expected
         final BigDecimal expectedGains = betAmount.multiply(new BigDecimal(gameOutcomeCount));
         final BigDecimal expectedBalance = originalBalance.add(expectedGains);

         // Assert
         Assert.assertTrue("Balance after all bets was not as expected " + expectedBalance + " but was "
               + afterBetsBalance, expectedBalance.compareTo(afterBetsBalance) == 0);

         // Return
         return null;
      }

   }

   /**
    * A task that places 11 bets, then manually throws a {@link ForcedTestException}.
    * This is so we may check that the balance transfers happened as 
    * expected from within the context of the Tx in which this task will run, but
    * also such that we can ensure that even if an exceptional case happens after bets have taken
    * place, the completed bets do not roll back.  Once the money's on the table, you can't take
    * it back. ;)
    */
   private final class Place11BetsThenForceExceptionTask implements Callable<Void>
   {
      /**
       * Tracks how many games won/lost.  A negative 
       * number indicates games lost; positive: won.
       */
      private int gameOutcomeCount = 0;

      private final BigDecimal betAmount;

      Place11BetsThenForceExceptionTask(final BigDecimal betAmount)
      {
         this.betAmount = betAmount;
      }

      @Override
      public Void call() throws Exception
      {

         // Find the starting balance
         final long alrubingerAccountId = ExampleUserData.ACCOUNT_ALRUBINGER_ID;
         final BigDecimal startingBalance = bank.getBalance(alrubingerAccountId);

         // Now run 11 bets
         for (int i = 0; i < 11; i++)
         {
            // Track whether we win or lose
            final boolean win = pokerGame.bet(ExampleUserData.ACCOUNT_ALRUBINGER_ID, betAmount);
            gameOutcomeCount += win ? 1 : -1;
         }
         log.info("Won " + gameOutcomeCount + " games at " + betAmount + "/game");

         // Get the user's balance after the bets
         final BigDecimal afterBetsBalance = bank.getBalance(alrubingerAccountId);

         // Ensure that money's been allocated properly
         new AssertGameOutcome(startingBalance, afterBetsBalance, gameOutcomeCount, betAmount).call();

         // Now force an exception to get a Tx rollback.  This should *not* affect the 
         // money already transferred during the bets, as they should have taken place
         // in nested Txs and already committed.
         throw new ForcedTestException();
      }

   }

   /**
    * A task that checks that the account balance of an {@link Account}
    * with specified ID equals a specified expected value.  Typically to be run 
    * inside of a Tx via {@link TransactionalPokerGameIntegrationTest#executeInTx(Callable...)}.
    *
    * @author <a href="mailto:andrew.rubinger@jboss.org">ALR</a>
    * @version $Revision: $
    */
   private final class CheckBalanceOfAccountTask implements Callable<Void>
   {

      private long accountId;

      private BigDecimal expectedBalance;

      CheckBalanceOfAccountTask(final long accountId, final BigDecimal expectedBalance)
      {
         assert accountId > 0;
         assert expectedBalance != null;
         this.accountId = accountId;
         this.expectedBalance = expectedBalance;
      }

      @Override
      public Void call() throws Exception
      {
         final Account account = db.find(Account.class, accountId);
         Assert.assertTrue("Balance was not as expected", expectedBalance.compareTo(account.getBalance()) == 0);
         return null;
      }

   }

   /**
    * Task which throws a {@link TaskExecutionException} for use in testing
    * for instance to force a Tx Rollback
    * 
    *
    * @author <a href="mailto:andrew.rubinger@jboss.org">ALR</a>
    * @version $Revision: $
    */
   private enum ForcedTestExceptionTask implements Callable<Void> {
      INSTANCE;

      @Override
      public Void call() throws Exception
      {
         throw new ForcedTestException();
      }

   }

   /**
    * Executes the specified tasks inside of a Tx, courtesy of the 
    * {@link TxWrappingLocalBusiness} view.
    */
   private void executeInTx(final Callable<?>... tasks) throws Throwable
   {
      // Precondition checks
      assert tasks != null : "Tasks must be specified";

      // Execute in a single new Tx, courtesy of the TxWrapping EJB
      try
      {
         txWrapper.wrapInTx(tasks);
      }
      catch (final TaskExecutionException tee)
      {
         // Unwrap the real cause
         throw tee.getCause();
      }
   }
}
