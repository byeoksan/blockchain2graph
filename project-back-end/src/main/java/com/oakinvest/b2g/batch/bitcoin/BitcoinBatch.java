package com.oakinvest.b2g.batch.bitcoin;

import com.oakinvest.b2g.domain.bitcoin.BitcoinAddress;
import com.oakinvest.b2g.domain.bitcoin.BitcoinBlock;
import com.oakinvest.b2g.domain.bitcoin.BitcoinTransaction;
import com.oakinvest.b2g.domain.bitcoin.BitcoinTransactionOutput;
import com.oakinvest.b2g.dto.bitcoin.core.BitcoindBlockData;
import com.oakinvest.b2g.dto.bitcoin.status.ApplicationStatus;
import com.oakinvest.b2g.dto.bitcoin.status.CurrentBlockStatusProcessStep;
import com.oakinvest.b2g.repository.bitcoin.BitcoinRepositories;
import com.oakinvest.b2g.service.bitcoin.BitcoinDataService;
import com.oakinvest.b2g.service.bitcoin.BitcoinDataServiceBufferLoader;
import com.oakinvest.b2g.util.bitcoin.batch.BitcoinBatchTemplate;
import com.oakinvest.b2g.util.bitcoin.exception.OriginTransactionNotFoundException;
import org.neo4j.ogm.session.SessionFactory;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Bitcoin import blocks batch.
 * Created by straumat on 27/02/17.
 */
@Component
public class BitcoinBatch extends BitcoinBatchTemplate {

    /**
     * Constructor.
     *
     * @param newBitcoinRepositories            bitcoin repositories
     * @param newBitcoinDataService             bitcoin data service
     * @param newBitcoinDataServiceBufferLoader bitcoin data service buffer loader
     * @param newStatusService                  status
     * @param newSessionFactory                 session factory
     */
    public BitcoinBatch(final BitcoinRepositories newBitcoinRepositories, final BitcoinDataService newBitcoinDataService, final BitcoinDataServiceBufferLoader newBitcoinDataServiceBufferLoader, final ApplicationStatus newStatusService, final SessionFactory newSessionFactory) {
        super(newBitcoinRepositories, newBitcoinDataService, newBitcoinDataServiceBufferLoader, newStatusService, newSessionFactory);
    }

    /**
     * Return the block to process.
     *
     * @return block to process.
     */
    @Override
    protected final Optional<Integer> getBlockHeightToProcess() {
        // We retrieve the next block to process according to the database.
        int blockToProcess = (int) (getBlockRepository().count() + 1);
        final Optional<Integer> totalBlockCount = getBitcoinDataService().getBlockCount();

        // We check if that next block exists by retrieving the block count.
        if (totalBlockCount.isPresent()) {
            // We update the global status of blockcount (if needed).
            if (totalBlockCount.get() != getStatus().getBlocksCountInBitcoinCore()) {
                getStatus().setBlocksCountInBitcoinCore(totalBlockCount.get());
            }
            // We return the block to process.
            if (blockToProcess <= totalBlockCount.get()) {
                getStatus().getCurrentBlockStatus().setBlockHeight(blockToProcess);
                return Optional.of(blockToProcess);
            } else {
                return Optional.empty();
            }
        } else {
            // Error while retrieving the number of blocks in core.
            return Optional.empty();
        }
    }

    /**
     * Process block.
     *
     * @param blockHeight block height to process.
     */
    @Override
    protected final Optional<BitcoinBlock> processBlock(final int blockHeight) {
        Optional<BitcoindBlockData> blockData = getBitcoinDataService().getBlockData(blockHeight);

        // -------------------------------------------------------------------------------------------------------------
        // If we have the data.
        if (blockData.isPresent()) {

            // ---------------------------------------------------------------------------------------------------------
            // We create the block to save. We retrieve the data from core and map it.
            final BitcoinBlock block = getMapper().blockDataToBitcoinBlock(blockData.get());

            // ---------------------------------------------------------------------------------------------------------
            // We get all the addresses.
            getStatus().getCurrentBlockStatus().setProcessStep(CurrentBlockStatusProcessStep.PROCESSING_ADDRESSES);
            addLog("Getting addresses from " + block.getTx().size() + " transaction(s)");
            final AtomicInteger addressesCounter = new AtomicInteger(0);
            final Map<String, BitcoinAddress> addressesCache = new ConcurrentHashMap<>();
            blockData.get().getAddresses()
                    .parallelStream() // In parallel.
                    .filter(Objects::nonNull) // If the address is not null.
                    .forEach(a -> {
                        Optional<BitcoinAddress> addressInRepository = getAddressRepository().findByAddressWithoutDepth(a);
                        if (addressInRepository.isPresent()) {
                            addressesCache.put(a, addressInRepository.get());
                            addLog("- Address " + a + " already exists");
                        } else {
                            addressesCache.put(a, new BitcoinAddress(a));
                            addLog("- Address " + a + " is new");
                        }
                        getStatus().getCurrentBlockStatus().setAddressesCount(addressesCounter.incrementAndGet());
                    });

            // ---------------------------------------------------------------------------------------------------------
            // We link the addresses to the input and the origin transaction.
            getStatus().getCurrentBlockStatus().setProcessStep(CurrentBlockStatusProcessStep.PROCESSING_TRANSACTIONS);
            final AtomicInteger transactionCounter = new AtomicInteger(0);
            final int txSize = block.getTx().size();
            addLog("Treating " + txSize + " transaction(s)");
            block.getTransactions()
                    .parallelStream()
                    .forEach(
                            t -> {
                                // -------------------------------------------------------------------------------------
                                // For each Vin.
                                t.getInputs()
                                        .stream()
                                        .filter(vin -> !vin.isCoinbase()) // If it's NOT a coinbase transaction.
                                        .forEach(vin -> {
                                            // -------------------------------------------------------------------------
                                            // We retrieve the original transaction.
                                            Optional<BitcoinTransactionOutput> originTransactionOutput = getTransactionOutputRepository().findByTxIdAndN(vin.getTxId(), vin.getvOut());

                                            // if we don't find in the database, this transaction must be in the block.
                                            if (!originTransactionOutput.isPresent()) {
                                                Optional<BitcoinTransaction> missingTransaction = block.getTransactions()
                                                        .stream()
                                                        .filter(o -> o.getTxId().equals(vin.getTxId()))
                                                        .findFirst();
                                                if (missingTransaction.isPresent() && missingTransaction.get().getOutputByIndex(vin.getvOut()).isPresent()) {
                                                    originTransactionOutput = Optional.of(missingTransaction.get().getOutputByIndex(vin.getvOut()).get());
                                                }
                                            }

                                            if (originTransactionOutput.isPresent()) {
                                                // -------------------------------------------------------------------------
                                                // We create the link.
                                                vin.setTransactionOutput(originTransactionOutput.get());

                                                // -------------------------------------------------------------------------
                                                // We set all the addresses linked to this input.
                                                originTransactionOutput.get().getAddresses()
                                                        .stream()
                                                        .filter(Objects::nonNull)
                                                        .forEach(a -> vin.setBitcoinAddress(addressesCache.get(a)));
                                            } else {
                                                //addError("In block " + getFormattedBlockHeight(block.getHeight()));
                                                //addError("Impossible to find the origin transaction of " + vin.getTxId() + " / " + vin.getvOut());
                                                throw new OriginTransactionNotFoundException("Origin transaction not found " + vin.getTxId() + " / " + vin.getvOut());
                                            }
                                        });

                                // -------------------------------------------------------------------------------------
                                // For each Vout.
                                t.getOutputs()
                                        .forEach(vout -> {
                                            // -------------------------------------------------------------------------
                                            // We set all the addresses linked to this output.
                                            vout.getAddresses()
                                                    .stream()
                                                    .filter(Objects::nonNull)
                                                    .forEach(a -> vout.setBitcoinAddress(addressesCache.get(a)));
                                        });

                                // -------------------------------------------------------------------------------------
                                // Save the transaction and add log to say we are done.
                                //getTransactionRepository().save(t);
                                getStatus().getCurrentBlockStatus().setTransactionsCount(transactionCounter.incrementAndGet());
                                addLog("- Transaction " + transactionCounter.get() + "/" + txSize + " created (" + t.getTxId() + " : " + t.getInputs().size() + " vin(s) & " + t.getOutputs().size() + " vout(s))");
                            });

            // ---------------------------------------------------------------------------------------------------------
            // We set the previous and the next block.
            Optional<BitcoinBlock> previousBlock = getBlockRepository().findByHeightWithoutDepth(block.getHeight() - 1);
            previousBlock.ifPresent(previous -> {
                block.setPreviousBlock(previous);
                addLog("Setting the previous block of this block");
                previous.setNextBlock(block);
                addLog("Setting this block as next block of the previous one");
            });

            // ---------------------------------------------------------------------------------------------------------
            // We return the block.
            return Optional.of(block);

        } else {
            // Or nothing if we did not retrieve the data.
            addError("No response from core for block n°" + getFormattedBlockHeight(blockHeight));
            return Optional.empty();
        }
    }

}