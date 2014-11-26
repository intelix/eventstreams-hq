/*
 * Copyright 2014 Intelix Pty Ltd
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

define(['react', 'coreMixin', 'app_content_nodetabs', 'app_ds_table', 'app_ds_editor'],
    function (React, coreMixin, Tabs, Table, EditBlock) {

        return React.createClass({
            mixins: [coreMixin],

            getInitialState: function () {
                return {selected: false}
            },

            subscribeToEvents: function() { return [
                ["addDatasource", this.openModal],
                ["editDatasource", this.openEditModal],
                ["modalClosed", this.closeModal],
                ["nodeSelectorForDatasources", this.handleSelectionEvent]
            ]},

            openModal: function (evt) {
                var defaults = {
                    "name": "",
                    "desc": "",
                    "sourceId": "",
                    "tags": "",
                    "initialState": "Stopped",
                    "source": {
                        "class": "file",
                        "directory": "",
                        "mainPattern": "",
                        "rollingPattern": ""
                    },
                    "sink": {
                        "class": "akka",
                        "url": "akka.tcp://ehub@localhost:12345/user/GATENAME"
                    }
                };
                this.setState({editBlock: <EditBlock addr={this.state.selected} addRoute="agents" title="Datasource configuration" defaults={defaults} />});
            },
            openEditModal: function (evt) {
                this.setState({editBlock: <EditBlock addr={this.state.selected} id={evt.detail.id} title="Datasource configuration"/>});
            },
            closeModal: function () {
                this.setState({editBlock: false});
            },

            handleSelectionEvent: function (evt) {
                this.setState({selected: evt.detail.address});
            },


            render: function () {

                return <div>

                    <Tabs roles={["hq"]} selectorId="nodeSelectorForDatasources" />
                    {this.state.editBlock ? this.state.editBlock : ""}
                    {this.state.selected ? <Table addr={this.state.selected}/> : ""}
                </div>;
            }
        });

    });