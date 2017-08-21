import { Connection } from './connection.js';
import { Bridge, setProtocolDebugEnabled } from './bridge.js';
import { ConnectionLostWidget, getCookie } from './utils.js';

// Export `setProtocolDebugEnabled` function
// to global scope
window['Bridge'] = {
  'setProtocolDebugEnabled': setProtocolDebugEnabled
};

window.document.addEventListener("DOMContentLoaded", () => {

  let config = window['KorolevConfig'];
  let clw = new ConnectionLostWidget(config['connectionLostWidget']);
  let connection = new Connection(
    getCookie('device'),
    config['sessionId'],
    config['serverRootPath'],
    window.location
  );

  connection.dispatcher.addEventListener('open', () => {
    clw.hide();
    let bridge = new Bridge(config, connection);
    let closeHandler = (event) => {
      bridge.destroy();
      clw.show();
      connection
        .dispatcher
        .removeEventListener('close', closeHandler);
    }
    connection
      .dispatcher
      .addEventListener('close', closeHandler);
  });

  connection.dispatcher.addEventListener('close', () => {
    // Reconnect
    connection.connect();
  });

  connection.connect();
});
