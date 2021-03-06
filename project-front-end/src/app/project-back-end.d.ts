/* tslint:disable */
// Generated using typescript-generator version 2.2.413 on 2018-05-21 12:01:43.

export interface ApplicationStatus extends Observable, Observer {
  blocksCountInBitcoinCore: number;
  blocksCountInNeo4j: number;
  currentBlockStatus: CurrentBlockStatus;
  averageBlockProcessDuration: number;
  lastErrorMessage: string;
}

export interface CurrentBlockStatus extends Observable {
  blockHeight: number;
  processStep: CurrentBlockStatusProcessStep;
  transactionsCount: number;
  addressesCount: number;
  loadedTransactions: number;
  processedAddresses: number;
  processedTransactions: number;
}

export interface Observable {
}

export interface Observer {
}

export const enum CurrentBlockStatusProcessStep {
  NO_BLOCK_TO_PROCESS = "NO_BLOCK_TO_PROCESS",
  NEW_BLOCK_TO_PROCESS = "NEW_BLOCK_TO_PROCESS",
  LOADING_TRANSACTIONS_FROM_BITCOIN_CORE = "LOADING_TRANSACTIONS_FROM_BITCOIN_CORE",
  PROCESSING_ADDRESSES = "PROCESSING_ADDRESSES",
  PROCESSING_TRANSACTIONS = "PROCESSING_TRANSACTIONS",
  SAVING_BLOCK = "SAVING_BLOCK",
  BLOCK_SAVED = "BLOCK_SAVED",
}
