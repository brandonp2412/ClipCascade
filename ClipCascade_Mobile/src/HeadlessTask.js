import {
  setDataInAsyncStorage,
  getDataFromAsyncStorage,
  clearAsyncStorage,
} from './AsyncStorageManagement'; // persistent storage
import StartForegroundService from './StartForegroundService'; // foreground service

module.exports = async data => {
  try {
    if (data && data['event'] === 'BOOT_COMPLETED') {
      const relaunch_on_boot = await getDataFromAsyncStorage(
        'relaunch_on_boot',
      );
      if (relaunch_on_boot !== null && relaunch_on_boot === 'true') {
        // `wsIsRunning` describes the previous process, not the user's desired
        // boot behavior. A reboot necessarily kills that process, so always
        // recreate the foreground sync service when relaunch-on-boot is enabled.
        await setDataInAsyncStorage('wsIsRunning', 'true');
        await setDataInAsyncStorage('wsForegroundServiceTerminated', 'false');
        await setDataInAsyncStorage('wsStatusMessage', '');
        const result = await StartForegroundService();
        if (result[0] === false) {
          throw result[1];
        }
      }
    }
  } catch (e) {
    console.error('Error in Headless JS Task:', e);
  }
};
