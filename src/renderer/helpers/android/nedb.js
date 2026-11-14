/**
 * Way data is stored for this database
 *
 * This version is the browser version and uses [localforage]{@link https://github.com/localForage/localForage} which chooses the best option depending on user browser (IndexedDB then WebSQL then localStorage).
 * @module storageBrowser
 * @see module:storage
 * @see module:storageReactNative
 * @private
 */

import { fileExists, getCurrentDataDirectory, getFullUri, readFileStrict, updateFilesInCurrentDataDirectory, writeFile, readFile } from './storage'

/**
 * A full rewrite goes to this sibling file first, see crashSafeWriteFileLinesAsync.
 * @param {string} filename
 */
const journalNameFor = (filename) => `${filename}~`

/**
 * The journal leads with the length of what follows, which is how a journal that was
 * only partly written is told apart from a complete one.
 * @param {string} journal
 * @returns {string | null} the journalled data, or null if the journal is incomplete
 */
export function readJournal(journal) {
  const headerEnd = journal.indexOf('\n')
  if (headerEnd === -1) {
    return null
  }
  const length = Number.parseInt(journal.slice(0, headerEnd), 10)
  const data = journal.slice(headerEnd + 1)
  return Number.isNaN(length) || data.length !== length ? null : data
}

/**
 * Returns Promise<true> if file exists.
 *
 * @param {string} file
 * @return {Promise<boolean>}
 * @async
 * @alias module:storageBrowser.existsAsync
 */
export async function existsAsync (file) {
  try {
    return await getFullUri(file) !== null
  } catch (error) {
    console.warn('NeDB - storage.android - existsAsync:', error)
    return false
  }
}

/**
 * Moves the item from one path to another.
 * @param {string} oldPath
 * @param {string} newPath
 * @return {Promise<void>}
 * @alias module:storageBrowser.renameAsync
 * @async
 */
export async function renameAsync (oldPath, newPath) {
  const oldUri = await getFullUri(oldPath)
  if (oldUri === null) {
    throw new Error(`Cannot rename missing file ${oldPath}`)
  }
  const content = await readFile(oldUri)
  const directory = await getCurrentDataDirectory()
  const newUri = await directory.createFile(newPath)
  const ok = await writeFile(newUri, content)
  if (!ok) {
    throw new Error(`Failed to write renamed file ${newPath}`)
  }
  directory.files[newPath] = newUri
  delete directory.files[oldPath]
  await updateFilesInCurrentDataDirectory(directory.files)
}

/**
 * Saves the item at given path.
 * @param {string} file
 * @param {string} data
 * @param {object} [options]
 * @return {Promise<void>}
 * @alias module:storageBrowser.writeFileAsync
 * @async
 */
export async function writeFileAsync(file, data, options) {
  let uri = await getFullUri(file)
  if (uri === null) {
    const directory = await getCurrentDataDirectory()
    uri = await directory.createFile(file)
  }
  const ok = await writeFile(uri, data)
  if (!ok) {
    throw new Error(`Failed to write ${file}`)
  }
}

/**
 * Append to the item at given path.
 * @function
 * @param {string} filename
 * @param {string} toAppend
 * @param {object} [options]
 * @return {Promise<void>}
 * @alias module:storageBrowser.appendFileAsync
 * @async
 */
export async function appendFileAsync(filename, toAppend, options) {
  let uri = await getFullUri(filename)
  if (uri === null) {
    const directory = await getCurrentDataDirectory()
    uri = await directory.createFile(filename)
  }
  const ok = await writeFile(uri, toAppend, true)
  if (!ok) {
    throw new Error(`Failed to append ${filename}`)
  }
}

/**
 * Read data at given path.
 * @function
 * @param {string} filename
 * @param {object} [options]
 * @return {Promise<Buffer>}
 * @alias module:storageBrowser.readFileAsync
 * @async
 */
export async function readFileAsync(filename, options) {
  const uri = await getFullUri(filename)
  if (uri === null) {
    throw new Error('File not found!')
  }
  try {
    return await readFileStrict(uri)
  } catch (error) {
    // A datafile that has not been created yet reads as empty. One that is there but
    // could not be read must not: nedb would load no documents and then write that
    // empty state back over the real data.
    if (!(await fileExists(uri))) {
      return ''
    }
    throw new Error(`Failed to read ${filename}: ${error?.message ?? error}`, { cause: error })
  }
}

/**
 * Async version of {@link module:storageBrowser.unlink}.
 * @function
 * @param {string} filename
 * @return {Promise<void>}
 * @async
 * @alias module:storageBrowser.unlink
 */
export async function unlinkAsync(filename) { return Promise.resolve() }

/**
 * Shim for {@link module:storage.mkdirAsync}, nothing to do, no directories will be used on the browser.
 * @function
 * @param {string} path
 * @param {object} [options]
 * @return {Promise<void|string>}
 * @alias module:storageBrowser.mkdirAsync
 * @async
 */
export async function mkdirAsync(path, options) { return Promise.resolve() }

/**
 * Shim for {@link module:storage.ensureParentDirectoryExistsAsync}, nothing to do, no directories will be used on the browser.
 * @function
 * @param {string} file
 * @param {number} [mode]
 * @return {Promise<void|string>}
 * @alias module:storageBrowser.ensureParentDirectoryExistsAsync
 * @async
 */
export async function ensureParentDirectoryExistsAsync(file, mode) { return Promise.resolve() }

/**
 * Shim for {@link module:storage.ensureDatafileIntegrityAsync}, nothing to do, no data corruption possible in the browser.
 * @param {string} filename
 * @return {Promise<void>}
 * @alias module:storageBrowser.ensureDatafileIntegrityAsync
 */
export async function ensureDatafileIntegrityAsync(filename) {
  const journalUri = await getFullUri(journalNameFor(filename))
  if (journalUri === null) {
    return
  }
  const journal = await readFile(journalUri)
  if (journal === '') {
    return
  }

  // A journal with content in it means a rewrite was interrupted. If it is complete the
  // interruption came while the datafile itself was being overwritten, so the datafile
  // cannot be trusted and is restored. If it is incomplete the datafile was never touched.
  const data = readJournal(journal)
  if (data !== null) {
    await writeFileAsync(filename, data)
  }
  await writeFile(journalUri, '')
}

/**
 * Fully write or rewrite the datafile, immune to crashes during the write operation (data will not be lost)
 * @param {string} filename
 * @param {string[]} lines
 * @return {Promise<void>}
 * @alias module:storageBrowser.crashSafeWriteFileLinesAsync
 */
export async function crashSafeWriteFileLinesAsync(filename, lines) {
  const data = [...lines, ''].join('\n')
  const journalName = journalNameFor(filename)

  // There is no atomic rename to lean on here, a rename is a copy. So the new contents
  // are journalled in full first, and the journal is only emptied once the datafile has
  // been overwritten. Whichever write is interrupted, ensureDatafileIntegrityAsync can
  // tell on the next load and one intact copy always survives.
  await writeFileAsync(journalName, `${data.length}\n${data}`)
  await writeFileAsync(filename, data)
  await writeFileAsync(journalName, '')
}
