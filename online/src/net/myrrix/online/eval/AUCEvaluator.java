/*
 * Copyright Myrrix Ltd
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package net.myrrix.online.eval;

import java.io.File;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import com.google.common.collect.Lists;
import com.google.common.collect.Multimap;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import org.apache.commons.math3.random.RandomGenerator;
import org.apache.mahout.cf.taste.common.NoSuchItemException;
import org.apache.mahout.cf.taste.common.NoSuchUserException;
import org.apache.mahout.cf.taste.common.TasteException;
import org.apache.mahout.cf.taste.impl.common.LongPrimitiveIterator;
import org.apache.mahout.cf.taste.recommender.RecommendedItem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.myrrix.common.MyrrixRecommender;
import net.myrrix.common.collection.FastByIDMap;
import net.myrrix.common.collection.FastIDSet;
import net.myrrix.common.random.RandomManager;
import net.myrrix.common.random.RandomUtils;
import net.myrrix.online.RescorerProvider;

/**
 * <p>This implementation calculates Area under curve (AUC), which may be understood as the probability
 * that a random "good" recommendation is ranked higher than a random "bad" recommendation.</p>
 * 
 * <p>This class can be run as a Java program; the single argument is a directory containing test data.
 * The {@link EvaluationResult} is printed to standard out.</p>
 *
 * @author Sean Owen
 */
public final class AUCEvaluator extends AbstractEvaluator {

  private static final Logger log = LoggerFactory.getLogger(AUCEvaluator.class);

  @Override
  protected boolean isSplitTestByPrefValue() {
    return true;
  }

  @Override
  public EvaluationResult evaluate(MyrrixRecommender recommender,
                                   RescorerProvider provider, // ignored
                                   Multimap<Long,RecommendedItem> testData) throws TasteException {
    FastByIDMap<FastIDSet> converted = new FastByIDMap<FastIDSet>(testData.size(), 1.25f);
    for (long userID : testData.keySet()) {
      Collection<RecommendedItem> userTestData = testData.get(userID);
      FastIDSet itemIDs = new FastIDSet(userTestData.size(), 1.25f);
      converted.put(userID, itemIDs);
      for (RecommendedItem datum : userTestData) {
        itemIDs.add(datum.getItemID());
      }
    }
    return evaluate(recommender, converted);
  }

  public EvaluationResult evaluate(final MyrrixRecommender recommender,
                                   final FastByIDMap<FastIDSet> testData) throws TasteException {

    final AtomicInteger underCurve = new AtomicInteger(0);
    final AtomicInteger total = new AtomicInteger(0);
    
    final long[] allItemIDs = recommender.getAllItemIDs().toArray();

    int numCores = Runtime.getRuntime().availableProcessors();
    ExecutorService executor = 
        Executors.newFixedThreadPool(numCores, new ThreadFactoryBuilder().setNameFormat("AUCEval-%s").build());
    List<Future<?>> futures = Lists.newArrayListWithCapacity(numCores);

    final LongPrimitiveIterator iterator = testData.keySetIterator();
    
    for (int i = 0; i < numCores; i++) {
      futures.add(executor.submit(new Callable<Object>() {
        private final RandomGenerator random = RandomManager.getRandom();        
        @Override
        public Object call() throws Exception {
          while (true) {
            long userID;
            synchronized (iterator) {
              if (!iterator.hasNext()) {
                return null;
              }
              userID = iterator.next();
            }
            
            FastIDSet testItemIDs = testData.get(userID);
            int numTest = testItemIDs.size();
            if (numTest == 0) {
              continue;
            }
      
            for (int i = 0; i < numTest; i++) {
      
              long randomTestItemID = RandomUtils.randomFrom(testItemIDs, random);
              long randomTrainingItemID;
              do {
                randomTrainingItemID = allItemIDs[random.nextInt(allItemIDs.length)];
              } while (testItemIDs.contains(randomTrainingItemID));
      
              float relevantEstimate;
              try {
                relevantEstimate = recommender.estimatePreference(userID, randomTestItemID);
              } catch (NoSuchItemException nsie) {
                // OK; it's possible item only showed up in test split
                continue;
              } catch (NoSuchUserException nsie) {
                // OK; it's possible user only showed up in test split
                continue;
              }
      
              float nonRelevantEstimate = recommender.estimatePreference(userID, randomTrainingItemID);
      
              if (relevantEstimate > nonRelevantEstimate) {
                underCurve.incrementAndGet();
              }
              total.incrementAndGet();
            }
          }
        }
      }));
    }
    
    executor.shutdown();
    
    int count = 0;
    for (Future<?> future : futures) {
      try {
        future.get();
      } catch (InterruptedException e) {
        throw new TasteException(e);
      } catch (ExecutionException e) {
        Throwable cause = e.getCause();
        if (cause instanceof TasteException) {
          throw (TasteException) cause;
        }
        throw new TasteException(cause);
      }
      if (++count % 1000 == 0) {
        log.info("AUC: {}", (double) underCurve.get() / total.get());
      }
    }

    double score = (double) underCurve.get() / total.get();
    log.info("AUC: {}", score);
    return new EvaluationResultImpl(score);
  }

  public static void main(String[] args) throws Exception {
    AUCEvaluator eval = new AUCEvaluator();
    EvaluationResult result = eval.evaluate(new File(args[0]));
    log.info(result.toString());
  }

}
